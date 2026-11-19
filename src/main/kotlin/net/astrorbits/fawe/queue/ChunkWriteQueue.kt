package net.astrorbits.fawe.queue

import com.sk89q.worldedit.coremc.internal.CoreMcWorld
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.internal.block.BlockStateIdAccess
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
import java.util.concurrent.Executors

/**
 * 按世界和区块暂存尚未写入的方块，并在服务器 tick 末尾按预算提交。
 *
 * [put]、[get] 和每个 tick 的取出都在服务器线程上执行。后台协程只把 WorldEdit 方块状态换成原版状态，
 * 不碰世界。写回 [net.minecraft.world.level.chunk.LevelChunkSection] 仍回到服务器线程。
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
     * 读取尚未落盘的方块。正在提交的区块也算。
     *
     * @param world 方块所属世界。
     * @return 队列里的状态；这个位置没有待写入变更时返回 null。
     */
    fun get(world: CoreMcWorld, x: Int, y: Int, z: Int): WeBlockState? {
        val pending = worlds[world.id()] ?: return null
        val chunkKey = ChunkPos.pack(x shr 4, z shr 4)
        val packed = BlockPos.asLong(x, y, z)
        pending.chunks[chunkKey]?.get(packed)?.let { return it.state }
        return pending.inFlight[chunkKey]?.get(packed)?.state
    }

    /**
     * 记下一次方块变更，替换同一位置上还没提交的状态。
     *
     * @param actor 写入时用来再次询问 [net.astrorbits.fawe.guard.RegionEditGuards] 的对象。
     */
    fun put(world: CoreMcWorld, x: Int, y: Int, z: Int, state: WeBlockState, actor: Actor?) {
        val pending = worlds.getOrPut(world.id()) { PendingWorld(world) }
        pending.world = world
        val chunkKey = ChunkPos.pack(x shr 4, z shr 4)
        val blocks = pending.chunks.getOrPut(chunkKey) { Long2ObjectOpenHashMap() }
        blocks.put(BlockPos.asLong(x, y, z), PendingBlock(state, actor))
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
                    state = block.state,
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

    private class PendingBlock(val state: WeBlockState, val actor: Actor?)
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
    val actor: Actor?,
)

internal class PreparedChange(
    val x: Int,
    val y: Int,
    val z: Int,
    val vanilla: net.minecraft.world.level.block.state.BlockState?,
    val fallback: WeBlockState,
    val actor: Actor?,
)
