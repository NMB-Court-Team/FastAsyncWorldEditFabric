package net.astrorbits.fawe.limit

import com.sk89q.worldedit.extension.platform.Actor
import org.enginehub.piston.CommandParameters
import org.enginehub.piston.gen.CommandCallListener
import org.enginehub.piston.inject.Key
import java.lang.reflect.Method

/**
 * 记住当前线程正在执行的 WorldEdit 命令执行者。
 *
 * 半径检查拿不到命令参数，只能从这里读取刚进入命令的 [Actor]。
 * 命令正常返回或抛出后清空，避免下一次非命令调用沿用上一个玩家。
 */
object FaweCommandContext : CommandCallListener {
    private val current = ThreadLocal<Actor?>()

    /**
     * @return 当前线程正在执行的命令执行者。命令之外为 null。
     */
    @JvmStatic
    fun actor(): Actor? = current.get()

    override fun beforeCall(method: Method, parameters: CommandParameters) {
        current.set(parameters.injectedValue(Key.of(Actor::class.java)).orElse(null))
    }

    override fun afterCall(method: Method, parameters: CommandParameters) {
        current.remove()
    }

    override fun afterThrow(method: Method, parameters: CommandParameters, error: Throwable) {
        current.remove()
    }
}
