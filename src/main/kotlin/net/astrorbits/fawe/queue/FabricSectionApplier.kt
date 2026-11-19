package net.astrorbits.fawe.queue

import com.sk89q.worldedit.coremc.internal.CoreMcLoggingProblemReporter
import com.sk89q.worldedit.coremc.internal.NBTConverter
import com.sk89q.worldedit.fabric.internal.FabricWorldEdit
import net.astrorbits.fawe.guard.RegionEditGuards
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.lighting.LightEngine
import net.minecraft.world.level.storage.TagValueInput
import org.enginehub.linbus.tree.LinCompoundTag
import org.enginehub.linbus.tree.LinTagType
import java.util.BitSet

/**
 * 在服务器线程把一个区块的变更写入 [LevelChunk] 的区段。
 *
 * 不走 `LevelChunk.setBlockState`，因此不会对每个方块触发邻居更新、放置回调或形状更新。
 * 高度图、方块实体和天空光来源仍按这个区块里实际改过的方块补齐。
 * 方块状态没变但带有 NBT 时，把标签载入方块实体，不重算高度图和光照。这个状态会带方块实体而实体还不在区块里时，先建一个再载入。
 * 方块的光照属性变了才按这个区块传播一次光照。区段里有方块状态变化，或变更带有 NBT 时，向正在跟踪该区块的玩家发一个区块包：光照没变时立刻发，变了则等光照任务结束后再发。
 */
object FabricSectionApplier {
    private val heightmaps = arrayOf(
        Heightmap.Types.MOTION_BLOCKING,
        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
        Heightmap.Types.OCEAN_FLOOR,
        Heightmap.Types.WORLD_SURFACE,
    )

    /**
     * @param snapshot 区块坐标和发起编辑的世界。
     * @param changes 已经换成原版方块状态的变更。`vanilla` 为空时在这条线程上用平台适配器再转一次。`nbt` 非空时，方块实体就位后载入这份标签。
     */
    internal fun apply(snapshot: ChunkSnapshot, changes: List<PreparedChange>) {
        val level = snapshot.world.world
        if (changes.isEmpty()) {
            return
        }
        val chunk = level.getChunk(snapshot.chunkX, snapshot.chunkZ)
        val emptyBefore = HashMap<Int, Boolean>()
        val touchedSections = BitSet()
        var lightChanged = false
        for (change in changes) {
            if (level.isOutsideBuildHeight(change.y)) {
                continue
            }
            if (!RegionEditGuards.allows(snapshot.world, change.x, change.y, change.z, change.actor)) {
                continue
            }
            val state = change.vanilla ?: FabricWorldEdit.getPlatform().adapter.toNativeBlockState(change.fallback)
            val sectionIndex = chunk.getSectionIndex(change.y)
            val section = chunk.getSection(sectionIndex)
            emptyBefore.putIfAbsent(sectionIndex, section.hasOnlyAir())
            val localX = change.x and 15
            val localY = change.y and 15
            val localZ = change.z and 15
            val previous = section.setBlockState(localX, localY, localZ, state, false)
            val nbt = change.nbt
            if (previous == state && nbt == null) {
                continue
            }
            touchedSections.set(sectionIndex)
            if (previous != state) {
                writeSurroundings(chunk, change.x, change.y, change.z, localX, localZ, previous, state)
                if (LightEngine.hasDifferentLightProperties(previous, state)) {
                    lightChanged = true
                }
            }
            if (nbt != null) {
                loadBlockEntity(level, chunk, change.x, change.y, change.z, state, nbt)
            }
        }
        if (touchedSections.isEmpty) {
            return
        }
        updateSectionEmptiness(level, chunk, emptyBefore, touchedSections)
        chunk.markUnsaved()
        relightAndSend(level, chunk, lightChanged)
    }

    private fun writeSurroundings(
        chunk: LevelChunk,
        x: Int,
        y: Int,
        z: Int,
        localX: Int,
        localZ: Int,
        previous: net.minecraft.world.level.block.state.BlockState,
        state: net.minecraft.world.level.block.state.BlockState,
    ) {
        for (type in heightmaps) {
            chunk.getOrCreateHeightmapUnprimed(type).update(localX, y, localZ, state)
        }
        val pos = BlockPos(x, y, z)
        if (previous.hasBlockEntity() && !state.shouldChangedStateKeepBlockEntity(previous)) {
            chunk.removeBlockEntity(pos)
        }
        if (state.hasBlockEntity() && state.block is EntityBlock) {
            val created = (state.block as EntityBlock).newBlockEntity(pos, state)
            if (created != null) {
                chunk.setBlockEntity(created)
            }
        }
        chunk.getSkyLightSources().update(chunk, localX, y, localZ)
    }

    /**
     * 把已经取到的标签载入这个位置的方块实体。
     *
     * `x`、`y`、`z` 写成目标坐标。标签里的 `id` 不是空白时写回 `id`。
     * 然后用 [net.minecraft.world.level.storage.TagValueInput] 和 `loadWithComponents` 载入，不触发邻居更新。
     * 方块实体还不存在、且这个状态的方块是 [EntityBlock] 时，先建一个空的再载入。建不出来时不载入，区段里的方块状态保持刚才的写入。
     *
     * @param level 方块所在的服务器世界，提供注册表。
     * @param chunk 目标区块。方块实体从这里读取或放回。
     * @param x 目标方块 X 坐标。
     * @param y 目标方块 Y 坐标。
     * @param z 目标方块 Z 坐标。
     * @param state 这一格已经写入区段的原版方块状态。
     * @param nbt 队列里保存的标签。载入前总会把 `x`、`y`、`z` 写成目标坐标；`id` 不是空白时才写回。
     */
    private fun loadBlockEntity(
        level: ServerLevel,
        chunk: LevelChunk,
        x: Int,
        y: Int,
        z: Int,
        state: net.minecraft.world.level.block.state.BlockState,
        nbt: LinCompoundTag,
    ) {
        val pos = BlockPos(x, y, z)
        val entity = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK)
            ?: createBlockEntity(chunk, pos, state)
            ?: return
        val builder = nbt.toBuilder()
            .putInt("x", x)
            .putInt("y", y)
            .putInt("z", z)
        val nbtId = nbt.findTag("id", LinTagType.stringTag())?.value() ?: ""
        if (nbtId.isNotBlank()) {
            builder.putString("id", nbtId)
        }
        val nativeTag = NBTConverter.toNative(builder.build())
        CoreMcLoggingProblemReporter.with({ "loading tile entity at $pos" }) { reporter ->
            val input = TagValueInput.create(reporter, level.registryAccess(), nativeTag)
            entity.loadWithComponents(input)
            entity.setChanged()
            true
        }
    }

    private fun createBlockEntity(
        chunk: LevelChunk,
        pos: BlockPos,
        state: net.minecraft.world.level.block.state.BlockState,
    ): BlockEntity? {
        val block = state.block
        if (block !is EntityBlock) {
            return null
        }
        val created = block.newBlockEntity(pos, state) ?: return null
        chunk.setBlockEntity(created)
        return created
    }

    private fun updateSectionEmptiness(
        level: ServerLevel,
        chunk: LevelChunk,
        emptyBefore: Map<Int, Boolean>,
        touchedSections: BitSet,
    ) {
        var sectionIndex = touchedSections.nextSetBit(0)
        while (sectionIndex >= 0) {
            val section = chunk.getSection(sectionIndex)
            val wasEmpty = emptyBefore[sectionIndex] == true
            val isEmpty = section.hasOnlyAir()
            if (wasEmpty != isEmpty) {
                val sectionY = chunk.getSectionYFromSectionIndex(sectionIndex)
                level.chunkSource.lightEngine.updateSectionStatus(SectionPos.of(chunk.pos, sectionY), isEmpty)
                level.chunkSource.onSectionEmptinessChanged(chunk.pos.x, sectionY, chunk.pos.z, isEmpty)
            }
            sectionIndex = touchedSections.nextSetBit(sectionIndex + 1)
        }
    }

    private fun relightAndSend(level: ServerLevel, chunk: LevelChunk, lightChanged: Boolean) {
        val engine = level.chunkSource.lightEngine
        if (lightChanged) {
            engine.propagateLightSources(chunk.pos)
            engine.tryScheduleUpdate()
        }
        val send = Runnable {
            val packet = ClientboundLevelChunkWithLightPacket(chunk, engine, null, null)
            for (player in level.chunkSource.chunkMap.getPlayers(chunk.pos, false)) {
                player.connection.send(packet)
            }
        }
        if (!lightChanged) {
            send.run()
            return
        }
        engine.waitForPendingTasks(chunk.pos.x, chunk.pos.z).whenComplete { _, _ ->
            level.server!!.execute(send)
        }
    }
}
