package net.astrorbits.fawe.queue

import com.sk89q.worldedit.fabric.internal.FabricWorldEdit
import net.astrorbits.fawe.guard.RegionEditGuards
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.lighting.LightEngine
import java.util.BitSet

/**
 * 在服务器线程把一个区块的变更写入 [LevelChunk] 的区段。
 *
 * 不走 `LevelChunk.setBlockState`，因此不会对每个方块触发邻居更新、放置回调或形状更新。
 * 高度图、方块实体和天空光来源仍按这个区块里实际改过的方块补齐。
 * 方块的光照属性变了才按这个区块传播一次光照。只要有方块实际变化，就向正在跟踪该区块的玩家发一个区块包：光照没变时立刻发，变了则等光照任务结束后再发。
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
     * @param changes 已经换成原版方块状态的变更。`vanilla` 为空时在这条线程上用平台适配器再转一次。
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
            if (previous == state) {
                continue
            }
            touchedSections.set(sectionIndex)
            writeSurroundings(chunk, change.x, change.y, change.z, localX, localZ, previous, state)
            if (LightEngine.hasDifferentLightProperties(previous, state)) {
                lightChanged = true
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
