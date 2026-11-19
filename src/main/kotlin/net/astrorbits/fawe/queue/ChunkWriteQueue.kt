package net.astrorbits.fawe.queue

import com.sk89q.worldedit.coremc.internal.CoreMcWorld
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.internal.block.BlockStateIdAccess
import com.sk89q.worldedit.world.block.BaseBlock
import com.sk89q.worldedit.world.block.BlockStateHolder
import com.sk89q.worldedit.world.block.BlockState as WeBlockState
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.astrorbits.fawe.FastAsyncWorldEditMain
import net.astrorbits.fawe.config.FaweConfig
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import org.enginehub.linbus.tree.LinCompoundTag
import java.util.concurrent.Executors

/**
 * 按世界和区块暂存尚未写入的方块，并在服务器 tick 末尾按预算提交。
 *
 * [put]、[get]、[flush] 和每个 tick 的取出都在服务器线程上执行。[put] 保存的是已经取到 NBT 的 [BaseBlock]。
 * 后台协程只把方块状态换成原版状态，不查看 NBT，也不访问方块实体。
 * tick 末尾取出的区块在后台转换后回到服务器线程，写入 [net.minecraft.world.level.chunk.LevelChunkSection]。
 * [flush] 在调用它的服务器线程上直接写入。
 */
object ChunkWriteQueue {
    private val worlds = HashMap<String, PendingWorld>()
    private var scope: CoroutineScope? = null
    private var dispatcher: ExecutorCoroutineDispatcher? = null

    /**
     * 按配置里的并行度启动后台调度，并在每个服务器 tick 末尾取出区块。
     *
     * 已经启动时不再登记第二次 tick 回调。服务器停止时关闭后台调度。
     */
    fun start() {
        if (scope != null) {
            return
        }
        val threads = FaweConfig.parallelism
        val executor = Executors.newFixedThreadPool(threads) { runnable ->
            Thread(runnable, "FastAsyncWorldEdit-Chunk").apply { isDaemon = true }
        }
        val nextDispatcher = executor.asCoroutineDispatcher()
        dispatcher = nextDispatcher
        scope = CoroutineScope(SupervisorJob() + nextDispatcher)
        ServerTickEvents.END_SERVER_TICK.register { server -> drain(server) }
        ServerLifecycleEvents.SERVER_STOPPING.register { stop() }
    }

    /**
     * 读取尚未落盘的方块，包含已经取到的 NBT。正在提交的区块也算。
     *
     * 只在服务器线程调用。
     *
     * @param world 方块所属世界，要与入队时的世界是同一个。
     * @param x 方块 X 坐标。
     * @param y 方块 Y 坐标。
     * @param z 方块 Z 坐标。
     * @return 队列里的方块；这个位置没有待写入变更时返回 null。
     */
    @JvmStatic
    fun get(world: CoreMcWorld, x: Int, y: Int, z: Int): BaseBlock? {
        val pending = worlds[world.id()] ?: return null
        val chunkKey = ChunkPos.pack(x shr 4, z shr 4)
        val packed = BlockPos.asLong(x, y, z)
        pending.chunks[chunkKey]?.get(packed)?.let { return it.block }
        return pending.inFlight[chunkKey]?.get(packed)?.block
    }

    /**
     * 把这个世界里还没交给后台的区块立刻写入区段。
     *
     * 必须在服务器线程调用。生成实体前调用，避免实体在支撑方块写入前开始下落。
     * 已经交给后台准备的区块不在这里等待，它们仍由对应的服务器任务写回。
     *
     * @param world 要写回的世界，要与入队时的世界是同一个。
     */
    @JvmStatic
    fun flush(world: CoreMcWorld) {
        val pending = worlds[world.id()] ?: return
        val chunkKeys = pending.chunks.keys.toList()
        for (chunkKey in chunkKeys) {
            val blocks = pending.chunks.remove(chunkKey) ?: continue
            if (blocks.isEmpty()) {
                continue
            }
            val snapshot = snapshot(pending.world, chunkKey, blocks)
            FabricSectionApplier.apply(snapshot, prepare(snapshot))
        }
    }

    /**
     * 在服务器线程把方块收成 [BaseBlock]，并取走 NBT。
     *
     * 必须在服务器线程调用。取 NBT 时可能读取世界里的方块实体。
     * 没有 NBT 时返回不带标签的方块。有 NBT 时，返回的方块持有已经算完的标签，不再使用入参里的 [com.sk89q.worldedit.util.concurrency.LazyReference]。
     *
     * @param block 要收下的方块。可以只有状态，也可以带方块实体标签。
     * @return NBT 已经取到的方块。没有标签时 NBT 为空。
     */
    fun capture(block: BlockStateHolder<*>): BaseBlock {
        val snapshot = block.toBaseBlock()
        val nbt = snapshot.nbt ?: return snapshot
        return snapshot.toImmutableState().toBaseBlock(nbt)
    }

    /**
     * 记下一次方块变更，替换同一位置上还没提交的方块。
     *
     * 必须在服务器线程调用。保存的是 [capture] 之后的方块，同一位置只留最后一次。
     *
     * @param world 方块所属世界。
     * @param x 方块 X 坐标。
     * @param y 方块 Y 坐标。
     * @param z 方块 Z 坐标。
     * @param block 要写入的方块。NBT 在这次调用里取走。
     * @param actor 写入时用来再次询问 [net.astrorbits.fawe.guard.RegionEditGuards] 的对象。没有行为主体时为 null。
     */
    fun put(world: CoreMcWorld, x: Int, y: Int, z: Int, block: BlockStateHolder<*>, actor: Actor?) {
        val pending = worlds.getOrPut(world.id()) { PendingWorld(world) }
        pending.world = world
        val chunkKey = ChunkPos.pack(x shr 4, z shr 4)
        val blocks = pending.chunks.getOrPut(chunkKey) { Long2ObjectOpenHashMap() }
        blocks.put(BlockPos.asLong(x, y, z), PendingBlock(capture(block), actor))
    }

    private fun drain(server: MinecraftServer) {
        val budget = FaweConfig.chunksPerTick
        val activeScope = scope ?: return
        var taken = 0
        for (pending in worlds.values) {
            if (taken >= budget) {
                break
            }
            val chunkKeys = pending.chunks.keys.toList()
            for (chunkKey in chunkKeys) {
                if (taken >= budget) {
                    break
                }
                val blocks = pending.chunks.remove(chunkKey) ?: continue
                if (blocks.isEmpty()) {
                    continue
                }
                pending.inFlight[chunkKey] = blocks
                val snapshot = snapshot(pending.world, chunkKey, blocks)
                taken++
                activeScope.launch {
                    val prepared = prepare(snapshot)
                    server.execute {
                        try {
                            FabricSectionApplier.apply(snapshot, prepared)
                        } catch (throwable: Throwable) {
                            FastAsyncWorldEditMain.LOGGER.error(
                                "Failed to apply chunk {}, {} in {}",
                                snapshot.chunkX,
                                snapshot.chunkZ,
                                snapshot.world.id(),
                                throwable,
                            )
                        } finally {
                            pending.inFlight.remove(chunkKey)
                        }
                    }
                }
            }
        }
    }

    private fun snapshot(world: CoreMcWorld, chunkKey: Long, blocks: Long2ObjectOpenHashMap<PendingBlock>): ChunkSnapshot {
        val changes = ArrayList<QueuedChange>(blocks.size)
        val iterator = blocks.long2ObjectEntrySet().iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val packed = entry.longKey
            val block = entry.value
            changes.add(
                QueuedChange(
                    x = BlockPos.getX(packed),
                    y = BlockPos.getY(packed),
                    z = BlockPos.getZ(packed),
                    state = block.block.toImmutableState(),
                    nbt = block.block.nbt,
                    actor = block.actor,
                ),
            )
        }
        return ChunkSnapshot(
            world = world,
            chunkX = ChunkPos.getX(chunkKey),
            chunkZ = ChunkPos.getZ(chunkKey),
            changes = changes,
        )
    }

    private fun prepare(snapshot: ChunkSnapshot): List<PreparedChange> {
        val prepared = ArrayList<PreparedChange>(snapshot.changes.size)
        for (change in snapshot.changes) {
            val id = BlockStateIdAccess.getBlockStateId(change.state)
            val vanilla = if (BlockStateIdAccess.isValidInternalId(id)) Block.stateById(id) else null
            prepared.add(
                PreparedChange(
                    x = change.x,
                    y = change.y,
                    z = change.z,
                    vanilla = vanilla,
                    fallback = change.state,
                    nbt = change.nbt,
                    actor = change.actor,
                ),
            )
        }
        return prepared
    }

    private fun stop() {
        scope?.cancel()
        scope = null
        dispatcher?.close()
        dispatcher = null
    }

    private class PendingWorld(var world: CoreMcWorld) {
        val chunks = HashMap<Long, Long2ObjectOpenHashMap<PendingBlock>>()
        val inFlight = HashMap<Long, Long2ObjectOpenHashMap<PendingBlock>>()
    }

    private class PendingBlock(val block: BaseBlock, val actor: Actor?)
}

internal class ChunkSnapshot(
    val world: CoreMcWorld,
    val chunkX: Int,
    val chunkZ: Int,
    val changes: List<QueuedChange>,
)

internal class QueuedChange(
    val x: Int,
    val y: Int,
    val z: Int,
    val state: WeBlockState,
    val nbt: LinCompoundTag?,
    val actor: Actor?,
)

internal class PreparedChange(
    val x: Int,
    val y: Int,
    val z: Int,
    val vanilla: net.minecraft.world.level.block.state.BlockState?,
    val fallback: WeBlockState,
    val nbt: LinCompoundTag?,
    val actor: Actor?,
)
