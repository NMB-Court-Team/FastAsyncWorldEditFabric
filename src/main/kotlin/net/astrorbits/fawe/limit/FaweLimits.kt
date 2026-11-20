package net.astrorbits.fawe.limit

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.util.formatting.text.Component
import com.sk89q.worldedit.util.formatting.text.TextComponent
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent
import net.astrorbits.fawe.config.FaweConfig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 按玩家权限合并 [FaweConfig] 里的限额组。
 *
 * 持有 `fawe.limit.unlimited` 时不封顶。否则把玩家持有的 `fawe.limit.<组名>` 按各项取较大值。
 * 一个组里小于 0 的数值表示这一项不叠加组上限：半径项回落到 WorldEdit 配置里的 `max-radius`/`max-brush-radius`，
 * 其余项不封顶。没有任何组命中时使用 `default`。
 *
 * 通过 [setOverride] 写入的覆盖优先于权限：命中覆盖的玩家不再看 `fawe.limit.*` 权限，
 * 直接使用覆盖组。覆盖只保存在内存里，玩家退出或服务器重启后恢复为按权限计算。
 */
object FaweLimits {
    const val UNLIMITED: String = "fawe.limit.unlimited"

    private val overrides: ConcurrentHashMap<UUID, FaweConfig.LimitGroup> = ConcurrentHashMap()

    /**
     * 把玩家的限额替换为指定组。覆盖对之后发起的编辑立即生效，与玩家权限无关。
     *
     * @param uniqueId 玩家 UUID。
     * @param group 新的限额组，方法会复制它；之后修改传入对象不影响已写入的覆盖。传 null 表示清除覆盖、恢复按权限计算。
     */
    @JvmStatic
    fun setOverride(uniqueId: UUID, group: FaweConfig.LimitGroup?) {
        val copy = group?.copy()
        if (copy == null) {
            overrides.remove(uniqueId)
        } else {
            overrides[uniqueId] = copy
        }
    }

    /**
     * 读取玩家的限额覆盖。
     *
     * @return 覆盖组的副本；该玩家没有覆盖时返回 null。
     */
    @JvmStatic
    fun getOverride(uniqueId: UUID): FaweConfig.LimitGroup? = overrides[uniqueId]?.copy()

    /**
     * 清除玩家的限额覆盖。
     *
     * @param uniqueId 玩家 UUID。
     */
    @JvmStatic
    fun clearOverride(uniqueId: UUID) {
        overrides.remove(uniqueId)
    }

    /**
     * @param actor 发起操作的对象。为 null 时使用 `default` 组。
     * @return 合并后的限额。不封顶的项为 -1。
     */
    fun resolve(actor: Actor?): FaweConfig.LimitGroup {
        FaweConfig.ensureLoaded()
        if (actor != null) {
            overrides[actor.uniqueId]?.let { return it.copy() }
        }
        if (actor != null && actor.hasPermission(UNLIMITED)) {
            return unlimitedGroup()
        }
        val groups = FaweConfig.limitGroups()
        val matched = ArrayList<FaweConfig.LimitGroup>()
        if (actor != null) {
            for ((name, group) in groups) {
                if (name != "default" && actor.hasPermission("fawe.limit.$name")) {
                    matched.add(group)
                }
            }
        }
        if (matched.isEmpty()) {
            return (groups["default"] ?: FaweConfig.LimitGroup()).copy()
        }
        return merge(matched)
    }

    /**
     * 把会话上的方块上限收紧到玩家的变更上限。
     *
     * @param actor 发起编辑的对象。
     * @param sessionLimit 会话当前的 `//limit`。-1 表示会话自己不限制。
     * @return 交给 [com.sk89q.worldedit.EditSession] 的上限。-1 表示不限制。
     */
    @JvmStatic
    fun blockChangeLimit(actor: Actor?, sessionLimit: Int): Int {
        val cap = resolve(actor).maxChanges
        if (cap < 0) {
            return sessionLimit
        }
        val capInt = cap.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (sessionLimit < 0) {
            return capInt
        }
        return minOf(sessionLimit, capInt)
    }

    /**
     * @param actor 当前命令的执行者。没有执行者时使用 `default` 组。组半径小于 0 时跳过这一项，只保留 WorldEdit 配置里大于等于 0 的上限。
     * @param radius 玩家请求的半径。
     * @param brush 为 true 时使用笔刷半径上限，不再看命令半径上限。
     * @return 超过上限时返回允许的最大半径；否则返回 null。
     */
    @JvmStatic
    fun radiusDenial(actor: Actor?, radius: Double, brush: Boolean): Int? {
        val config = WorldEdit.getInstance().configuration
        val group = resolve(actor)
        val caps = ArrayList<Int>()
        if (brush) {
            addPositive(caps, config.maxBrushRadius)
            addPositive(caps, group.maxBrushRadius)
        } else {
            addPositive(caps, config.maxRadius)
            addPositive(caps, group.maxRadius)
        }
        if (caps.isEmpty()) {
            return null
        }
        val allowed = caps.min()
        return if (radius > allowed) allowed else null
    }

    /**
     * 把表达式超时收紧到限额。已经更小的会话超时保持不变。
     *
     * @param actor 玩家或控制台。
     * @param sessionTimeout 会话里的超时，单位是毫秒。-1 表示会话不限制。
     * @return 实际应使用的超时，单位是毫秒。
     */
    @JvmStatic
    fun expressionTimeout(actor: Actor?, sessionTimeout: Int): Int {
        val cap = resolve(actor).maxExpressionMs
        if (cap < 0) {
            return sessionTimeout
        }
        if (sessionTimeout < 0) {
            return cap
        }
        return minOf(sessionTimeout, cap)
    }

    private val failures = ThreadLocal.withInitial { 0 }

    /**
     * 开始一次编辑前清掉拒绝计数。
     */
    @JvmStatic
    fun beginEdit() {
        failures.set(0)
    }

    /**
     * 区域守卫拒绝一个方块时调用。
     *
     * @param actor 发起编辑的对象。
     * @throws FaweLimitException 拒绝次数超过 `maxFails` 时抛出。
     */
    fun recordFailure(actor: Actor?) {
        val next = failures.get() + 1
        failures.set(next)
        val cap = resolve(actor).maxFails
        if (cap >= 0 && next > cap) {
            throw limitExceeded("fastasyncworldedit.limit.fails", cap.toString())
        }
    }

    /**
     * 笔刷循环每进入一轮调用一次。
     *
     * @param actor 使用笔刷的玩家。
     * @param iteration 从 1 开始的轮数。
     * @throws FaweLimitException 超过 `maxIterations` 时抛出。
     */
    fun checkIteration(actor: Actor?, iteration: Int) {
        val cap = resolve(actor).maxIterations
        if (cap >= 0 && iteration > cap) {
            throw limitExceeded("fastasyncworldedit.limit.iterations", cap.toString())
        }
    }

    private fun addPositive(caps: MutableList<Int>, value: Int) {
        if (value >= 0) {
            caps.add(value)
        }
    }

    private fun unlimitedGroup(): FaweConfig.LimitGroup {
        val group = FaweConfig.LimitGroup()
        group.maxChanges = -1
        group.maxChecks = -1
        group.maxFails = -1
        group.maxIterations = -1
        group.maxEntities = -1
        group.maxBlockStates = -1
        group.maxRadius = -1
        group.maxBrushRadius = -1
        group.maxExpressionMs = -1
        group.maxHistoryMb = -1
        return group
    }

    private fun merge(groups: List<FaweConfig.LimitGroup>): FaweConfig.LimitGroup {
        val merged = FaweConfig.LimitGroup()
        merged.maxChanges = widestLong(groups) { it.maxChanges }
        merged.maxChecks = widestLong(groups) { it.maxChecks }
        merged.maxFails = widestInt(groups) { it.maxFails }
        merged.maxIterations = widestInt(groups) { it.maxIterations }
        merged.maxEntities = widestInt(groups) { it.maxEntities }
        merged.maxBlockStates = widestInt(groups) { it.maxBlockStates }
        merged.maxRadius = widestInt(groups) { it.maxRadius }
        merged.maxBrushRadius = widestInt(groups) { it.maxBrushRadius }
        merged.maxExpressionMs = widestInt(groups) { it.maxExpressionMs }
        merged.maxHistoryMb = widestInt(groups) { it.maxHistoryMb }
        return merged
    }

    private fun widestLong(groups: List<FaweConfig.LimitGroup>, read: (FaweConfig.LimitGroup) -> Long): Long {
        var best = 0L
        for (group in groups) {
            val value = read(group)
            if (value < 0) {
                return -1
            }
            if (value > best) {
                best = value
            }
        }
        return best
    }

    private fun widestInt(groups: List<FaweConfig.LimitGroup>, read: (FaweConfig.LimitGroup) -> Int): Int {
        var best = 0
        for (group in groups) {
            val value = read(group)
            if (value < 0) {
                return -1
            }
            if (value > best) {
                best = value
            }
        }
        return best
    }

    internal fun limitExceeded(key: String, limit: String): FaweLimitException {
        return FaweLimitException(TranslatableComponent.of(key, TextComponent.of(limit)))
    }
}

/**
 * 玩家超过 `fastasyncworldedit.json` 里的某一项限额。
 *
 * @param message 发给玩家的翻译文本。
 */
class FaweLimitException(message: Component) : com.sk89q.worldedit.WorldEditException(message)
