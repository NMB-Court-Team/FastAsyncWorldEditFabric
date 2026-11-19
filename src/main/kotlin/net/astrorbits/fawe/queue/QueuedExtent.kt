package net.astrorbits.fawe.queue

import com.sk89q.worldedit.coremc.internal.CoreMcWorld
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.extent.AbstractDelegateExtent
import com.sk89q.worldedit.extent.Extent
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.world.World
import com.sk89q.worldedit.world.block.BaseBlock
import com.sk89q.worldedit.world.block.BlockStateHolder
import net.astrorbits.fawe.guard.RegionEditGuards

/**
 * 把方块变更按区块收进 [ChunkWriteQueue]，不把每个方块直接交给世界。
 *
 * 挂在 ChangeSet 内侧。撤销仍由 WorldEdit 的内存历史重放，重放也会进这条队列。
 * 非 [CoreMcWorld] 的范围原样交给内层。
 *
 * @param extent ChangeSet 内侧的下一层范围。
 * @param world 这次会话正在编辑的世界。
 * @param actor 发起编辑的对象。没有行为主体时为 null。控制台是非空的 [Actor]。
 */
class QueuedExtent(
    extent: Extent,
    private val world: World,
    private val actor: Actor?,
) : AbstractDelegateExtent(extent) {

    override fun getFullBlock(position: BlockVector3): BaseBlock {
        val queued = queuedState(position)
        if (queued != null) {
            return queued.toBaseBlock()
        }
        return super.getFullBlock(position)
    }

    override fun <T : BlockStateHolder<T>> setBlock(location: BlockVector3, block: T): Boolean {
        val levelWorld = world as? CoreMcWorld ?: return super.setBlock(location, block)
        if (!RegionEditGuards.allows(world, location.x(), location.y(), location.z(), actor)) {
            return false
        }
        val next = block.toImmutableState()
        val current = getBlock(location)
        if (current == next) {
            return false
        }
        ChunkWriteQueue.put(levelWorld, location.x(), location.y(), location.z(), next, actor)
        return true
    }

    override fun getBlock(position: BlockVector3): com.sk89q.worldedit.world.block.BlockState {
        val queued = queuedState(position)
        if (queued != null) {
            return queued
        }
        return super.getBlock(position)
    }

    private fun queuedState(position: BlockVector3): com.sk89q.worldedit.world.block.BlockState? {
        val levelWorld = world as? CoreMcWorld ?: return null
        return ChunkWriteQueue.get(levelWorld, position.x(), position.y(), position.z())
    }
}
