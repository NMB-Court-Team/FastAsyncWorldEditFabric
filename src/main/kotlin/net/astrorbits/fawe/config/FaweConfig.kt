package net.astrorbits.fawe.config

import com.google.gson.GsonBuilder
import net.astrorbits.fawe.FastAsyncWorldEditMain
import net.astrorbits.fawe.internal.FaweHistory
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * `config/fastasyncworldedit.json` 的配置。
 *
 * [parallelism] 和 [chunksPerTick] 控制后台整理和主线程提交。
 * [history] 只决定新建编辑会话要不要包内存 ChangeSet，不参与队列调度。
 *
 * 文件在 [load] 时读取。缺文件时按默认值创建并写入。已有文件里小于 1 的数值会收到 1 并写回。
 *
 * @property parallelism 后台整理区块的线程数，至少为 1。
 * @property chunksPerTick 每个服务器 tick 从队列取出并提交的区块数，至少为 1。
 * @property history 为 true 时新建的编辑会话包上内存 ChangeSet，`//undo` 可以撤销。
 */
object FaweConfig {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path = FabricLoader.getInstance().configDir.resolve("fastasyncworldedit.json")

    @Volatile
    private var loaded = Stored().apply { parallelism = defaultParallelism() }

    val parallelism: Int
        get() = loaded.parallelism

    val chunksPerTick: Int
        get() = loaded.chunksPerTick

    val history: Boolean
        get() = loaded.history

    /**
     * 读取配置并同步内存撤销开关。
     *
     * 缺文件时写入默认值。文件已存在但为空或无法解析时，只在内存里用默认值，不改磁盘上的文件。
     * 数值小于 1 时收到 1；和读入值不同才会写回。
     */
    fun load() {
        val defaults = Stored().apply { parallelism = defaultParallelism() }
        val parsed = readFile() ?: defaults
        val sanitized = parsed.sanitized()
        val missing = !Files.exists(path)
        if (missing || !sanitized.sameAs(parsed)) {
            writeFile(sanitized)
        }
        loaded = sanitized
        FaweHistory.setRecord(sanitized.history)
        FastAsyncWorldEditMain.LOGGER.info(
            "Loaded config: parallelism={}, chunksPerTick={}, history={}",
            sanitized.parallelism,
            sanitized.chunksPerTick,
            sanitized.history,
        )
    }

    private fun readFile(): Stored? {
        if (!Files.exists(path)) {
            return null
        }
        return try {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                gson.fromJson(reader, Stored::class.java)
            }
        } catch (exception: Exception) {
            FastAsyncWorldEditMain.LOGGER.warn("Failed to read {}, using defaults", path, exception)
            null
        }
    }

    private fun writeFile(stored: Stored) {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
            gson.toJson(stored, writer)
        }
    }

    private fun defaultParallelism(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /**
     * Gson 读写的配置体。无参构造的字段初值是 JSON 缺字段时的回退：`parallelism` 为 1，`chunksPerTick` 为 16，`history` 为 true。
     * 缺文件或解析失败时，[load] 另建一份并把 `parallelism` 设成当前 CPU 数，不用上面的 1。
     * [sanitized] 只把小于 1 的线程数和每 tick 区块数收到 1。
     */
    private class Stored {
        var parallelism: Int = 1
        var chunksPerTick: Int = 16
        var history: Boolean = true

        fun sanitized(): Stored {
            val copy = Stored()
            copy.parallelism = parallelism.coerceAtLeast(1)
            copy.chunksPerTick = chunksPerTick.coerceAtLeast(1)
            copy.history = history
            return copy
        }

        fun sameAs(other: Stored): Boolean {
            return parallelism == other.parallelism &&
                chunksPerTick == other.chunksPerTick &&
                history == other.history
        }
    }
}
