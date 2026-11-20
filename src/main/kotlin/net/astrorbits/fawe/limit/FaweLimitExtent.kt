package net.astrorbits.fawe.limit

import com.sk89q.worldedit.entity.BaseEntity
import com.sk89q.worldedit.entity.Entity
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.extent.AbstractDelegateExtent
import com.sk89q.worldedit.extent.Extent
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.util.Location
import com.sk89q.worldedit.world.biome.BiomeType
import com.sk89q.worldedit.world.block.BaseBlock
import com.sk89q.worldedit.world.block.BlockState
import com.sk89q.worldedit.world.block.BlockStateHolder

/**
 * 统计一次编辑里的 `getBlock`、`getFullBlock`、`setBiome`，以及 `createEntity` 和带 NBT 的 `setBlock`。
 *
 * 方块变更数量仍由 WorldEdit 的 `BlockChangeLimiter` 统计。
 * 这里放在历史外侧，撤销重放不经过这一层。
 *
 * @param extent 下一层范围。
 * @param actor 发起这次编辑的对象。为 null 时按 `default` 限额组计算。
 */
class FaweLimitExtent(
    extent: Extent,
    private val actor: Actor?,
) : AbstractDelegateExtent(extent) {
    private val limits = FaweLimits.resolve(actor)
    private var checks = 0L
    private var entities = 0
    private var tiles = 0

    override fun getBlock(position: BlockVector3): BlockState {
        countCheck()
        return super.getBlock(position)
    }

    override fun getFullBlock(position: BlockVector3): BaseBlock {
        countCheck()
        return super.getFullBlock(position)
    }

    override fun <T : BlockStateHolder<T>> setBlock(location: BlockVector3, block: T): Boolean {
        if (block.toBaseBlock().nbt != null) {
            tiles++
            val cap = limits.maxBlockStates
            if (cap >= 0 && tiles > cap) {
                throw FaweLimits.limitExceeded("fastasyncworldedit.limit.blockstates", cap.toString())
            }
        }
        return super.setBlock(location, block)
    }

    override fun setBiome(position: BlockVector3, biome: BiomeType): Boolean {
        countCheck()
        return super.setBiome(position, biome)
    }

    override fun createEntity(location: Location, entity: BaseEntity): Entity? {
        entities++
        val cap = limits.maxEntities
        if (cap >= 0 && entities > cap) {
            throw FaweLimits.limitExceeded("fastasyncworldedit.limit.entities", cap.toString())
        }
        return super.createEntity(location, entity)
    }

    private fun countCheck() {
        checks++
        val cap = limits.maxChecks
        if (cap >= 0 && checks > cap) {
            throw FaweLimits.limitExceeded("fastasyncworldedit.limit.checks", cap.toString())
        }
    }
}
