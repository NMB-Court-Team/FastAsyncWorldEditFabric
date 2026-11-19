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
 * [getFullBlock] 在这个坐标有待写入或正在提交的记录时，返回队列里的方块。那份方块有标签时带 NBT，没有时 NBT 为空。[getBlock] 只返回方块状态。
 * 新方块没有 NBT 时，状态与当前位置相同则不入队。有 NBT 时，状态和标签都相同才不入队。
 * 非 [CoreMcWorld] 的范围原样交给内层。区域守卫拒绝时返回 false，方块不入队。
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
        val queued = queuedBlock(position)
        if (queued != null) {
            return queued
        }
        return super.getFullBlock(position)
    }

    override fun <T : BlockStateHolder<T>> setBlock(location: BlockVector3, block: T): Boolean {
        val levelWorld = world as? CoreMcWorld ?: return super.setBlock(location, block)
        if (!RegionEditGuards.allows(world, location.x(), location.y(), location.z(), actor)) {
            return false
        }
        val captured = ChunkWriteQueue.capture(block)
        val next = captured.toImmutableState()
        val current = getBlock(location)
        if (current == next && keepsSameTile(captured, location)) {
            return false
        }
        ChunkWriteQueue.put(levelWorld, location.x(), location.y(), location.z(), captured, actor)
        return true
    }

    override fun getBlock(position: BlockVector3): com.sk89q.worldedit.world.block.BlockState {
        val queued = queuedBlock(position)
        if (queued != null) {
            return queued.toImmutableState()
        }
        return super.getBlock(position)
    }

    /**
     * 新方块没有 NBT 时返回 true。有 NBT 时，与 [getFullBlock] 读到的标签相同才返回 true。
     *
     * 调用前方块状态已经相同。状态不同的变更不会进入这里。
     */
    private fun keepsSameTile(captured: BaseBlock, location: BlockVector3): Boolean {
        val nbt = captured.nbt ?: return true
        return nbt == getFullBlock(location).nbt
    }

    private fun queuedBlock(position: BlockVector3): BaseBlock? {
        val levelWorld = world as? CoreMcWorld ?: return null
        return ChunkWriteQueue.get(levelWorld, position.x(), position.y(), position.z())
    }
}
