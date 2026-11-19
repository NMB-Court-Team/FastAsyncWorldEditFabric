package net.astrorbits.fawe.guard

import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.world.World
import net.astrorbits.fawe.FastAsyncWorldEditMain
import net.fabricmc.loader.api.FabricLoader
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 队列把方块写入世界之前的编辑拦截。
 *
 * 默认没有实现，所有方块都允许写入。以后的 Fabric WorldGuard 实现 [RegionEditGuardEntrypoint]，
 * 入口点键是 `fastasyncworldedit:region_guard`。多个实现都允许时才会写入。
 */
fun interface RegionEditGuard {
    /**
     * @param world 正在编辑的 WorldEdit 世界。
     * @param x 方块世界坐标 X。
     * @param y 方块世界坐标 Y。
     * @param z 方块世界坐标 Z。
     * @param actor 发起这次编辑的对象。没有行为主体时为 null。控制台是非空的 [Actor]。
     * @return 为 false 时这个方块不会写入区块。
     */
    fun allows(world: World, x: Int, y: Int, z: Int, actor: Actor?): Boolean
}

/**
 * Fabric 入口点。实现类需要无参构造。
 */
interface RegionEditGuardEntrypoint {
    /**
     * @return 启动时注册的拦截实现。
     */
    fun create(): RegionEditGuard
}

/**
 * 已注册的 [RegionEditGuard]。
 */
object RegionEditGuards {
    private val guards = CopyOnWriteArrayList<RegionEditGuard>()

    /**
     * 读取 `fastasyncworldedit:region_guard` 入口点并注册。
     *
     * 某个入口点构造失败时记下日志并继续加载其余入口点。
     */
    fun loadEntrypoints() {
        val containers = FabricLoader.getInstance()
            .getEntrypointContainers("fastasyncworldedit:region_guard", RegionEditGuardEntrypoint::class.java)
        for (container in containers) {
            try {
                register(container.entrypoint.create())
            } catch (exception: Throwable) {
                FastAsyncWorldEditMain.LOGGER.error(
                    "Failed to load region edit guard from {}",
                    container.provider.metadata.id,
                    exception,
                )
            }
        }
    }

    /**
     * @param guard 额外的拦截实现。后注册的同样参与判断。
     */
    fun register(guard: RegionEditGuard) {
        guards.add(guard)
    }

    /**
     * @return 已注册实现的数量。为 0 时 [allows] 直接放行。
     */
    fun size(): Int = guards.size

    /**
     * @param world 正在编辑的世界。
     * @param x 方块世界坐标 X。
     * @param y 方块世界坐标 Y。
     * @param z 方块世界坐标 Z。
     * @param actor 发起编辑的对象，可以为 null。
     * @return 没有拦截实现，或每一个实现都允许时返回 true。
     */
    fun allows(world: World, x: Int, y: Int, z: Int, actor: Actor?): Boolean {
        for (guard in guards) {
            if (!guard.allows(world, x, y, z, actor)) {
                return false
            }
        }
        return true
    }
}
