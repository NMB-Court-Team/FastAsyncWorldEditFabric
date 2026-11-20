package net.astrorbits.fawe.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.astrorbits.fawe.FastAsyncWorldEditMain
import net.astrorbits.fawe.internal.FaweHistory
import net.astrorbits.fawe.limit.FaweLimits
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * `config/fastasyncworldedit.json` 的配置。
 *
 * [parallelism] 和 [chunksPerTick] 控制后台整理和主线程提交。
 * [history] 为 true 时，新建的编辑会话把变更写入磁盘历史文件，`//undo` 可以撤销。
 * 限额组、图片下载、网页剪贴板和历史保留天数也在这份文件里。
 *
 * 文件在 [load] 时读取。缺文件时按默认值创建并写入。已有文件缺字段时，内存里使用默认值；只有规范化之后的结果和读入值不同才写回。
 * 小于 1 的线程数、每 tick 区块数、历史保留天数、图片像素上限和图片下载超时会收到 1。
 *
 * @property parallelism 后台整理区块的线程数，至少为 1。
 * @property chunksPerTick 每个服务器 tick 从队列取出并提交的区块数，至少为 1。
 * @property history 为 true 时新建的编辑会话写入磁盘历史。为 false 时，新建的编辑不写入历史，也不进入撤销栈。已经在栈里的记录仍可撤销。
 * @property historyRetentionDays 历史文件保留天数，至少为 1。超过后在启动时删除。
 * @property imageHosts 图片笔刷和 stencil 允许下载的主机名，不含端口。
 * @property imageMaxPixels 图片宽乘高的上限。
 * @property imageLoadTimeoutSeconds 下载图片的超时，单位是秒。
 * @property webUrl 网页剪贴板根地址，`url:` 加载会拼到这个地址后面。以 `/` 结尾。
 * @property arkitektonikaBackend Arkitektonika 上传地址，不含末尾的 `/upload`。
 * @property arkitektonikaDownload 下载链接模板，`{key}` 会换成下载密钥。
 * @property arkitektonikaDelete 删除链接模板，`{key}` 会换成删除密钥。
 * @property schematicDownloadHosts `//schem load` 允许下载的 https 主机名。
 */
object FaweConfig {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = FabricLoader.getInstance().configDir.resolve("fastasyncworldedit.json")

    @Volatile
    private var loaded: Stored = defaultStored()

    @Volatile
    private var loadedOnce: Boolean = false

    val parallelism: Int
        get() = loaded.parallelism

    val chunksPerTick: Int
        get() = loaded.chunksPerTick

    val history: Boolean
        get() = loaded.history

    val historyRetentionDays: Int
        get() = loaded.historyRetentionDays

    val imageHosts: List<String>
        get() = loaded.imageHosts.toList()

    val imageMaxPixels: Int
        get() = loaded.imageMaxPixels

    val imageLoadTimeoutSeconds: Int
        get() = loaded.imageLoadTimeoutSeconds

    val webUrl: String
        get() = loaded.webUrl

    val arkitektonikaBackend: String
        get() = loaded.arkitektonikaBackend

    val arkitektonikaDownload: String
        get() = loaded.arkitektonikaDownload

    val arkitektonikaDelete: String
        get() = loaded.arkitektonikaDelete

    val schematicDownloadHosts: List<String>
        get() = loaded.schematicDownloadHosts.toList()

    /**
     * 已加载的限额组。键是组名，`default` 一定存在。
     *
     * 数值小于 0 的半径表示改用 WorldEdit 配置里的半径。
     * 数值小于 0 的变更、检查、实体、方块实体、失败、迭代或表达式时间表示这一项不封顶。
     */
    fun limitGroups(): Map<String, LimitGroup> = loaded.limits

    /**
     * 读取配置并同步历史开关和限额节点。
     *
     * 缺文件时写入默认值。文件已存在但为空或无法解析时，只在内存里用默认值，不改磁盘上的文件。
     * 和读入值不同才会写回。
     */
    fun load() {
        if (loading) {
            return
        }
        loading = true
        try {
            val parsed = readFile() ?: defaultStored()
            val sanitized = parsed.sanitized()
            val missing = !Files.exists(path)
            if (missing || !sanitized.sameAs(parsed)) {
                writeFile(sanitized)
            }
            loaded = sanitized
            loadedOnce = true
            FaweHistory.setRecord(sanitized.history)
            FaweLimits.registerNodes()
            FastAsyncWorldEditMain.LOGGER.info(
                "Loaded config: parallelism={}, chunksPerTick={}, history={}, limitGroups={}",
                sanitized.parallelism,
                sanitized.chunksPerTick,
                sanitized.history,
                sanitized.limits.keys,
            )
        } finally {
            loading = false
        }
    }

    /**
     * 配置还没读过时调用 [load]。已经读过则直接返回。
     */
    fun ensureLoaded() {
        if (!loadedOnce) {
            load()
        }
    }

    /**
     * 历史文件根目录：`config/fastasyncworldedit/history`。
     */
    fun historyDirectory(): Path = FabricLoader.getInstance().configDir.resolve("fastasyncworldedit").resolve("history")

    /**
     * 笔刷文件根目录：`config/fastasyncworldedit/brushes`。
     */
    fun brushDirectory(): Path = FabricLoader.getInstance().configDir.resolve("fastasyncworldedit").resolve("brushes")

    private fun readFile(): Stored? {
        if (!Files.exists(path)) {
            return null
        }
        return try {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                val json = gson.fromJson(reader, JsonObject::class.java) ?: return null
                overlay(json)
            }
        } catch (exception: Exception) {
            FastAsyncWorldEditMain.LOGGER.warn("Failed to read {}, using defaults", path, exception)
            null
        }
    }

    private fun overlay(json: JsonObject): Stored {
        val stored = Stored()
        json.optionalInt("parallelism")?.let { stored.parallelism = it }
        json.optionalInt("chunksPerTick")?.let { stored.chunksPerTick = it }
        json.optionalBoolean("history")?.let { stored.history = it }
        json.optionalInt("historyRetentionDays")?.let { stored.historyRetentionDays = it }
        json.optionalStringList("imageHosts")?.let { stored.imageHosts = ArrayList(it) }
        json.optionalInt("imageMaxPixels")?.let { stored.imageMaxPixels = it }
        json.optionalInt("imageLoadTimeoutSeconds")?.let { stored.imageLoadTimeoutSeconds = it }
        json.optionalString("webUrl")?.let { stored.webUrl = it }
        json.optionalString("arkitektonikaBackend")?.let { stored.arkitektonikaBackend = it }
        json.optionalString("arkitektonikaDownload")?.let { stored.arkitektonikaDownload = it }
        json.optionalString("arkitektonikaDelete")?.let { stored.arkitektonikaDelete = it }
        json.optionalStringList("schematicDownloadHosts")?.let { stored.schematicDownloadHosts = ArrayList(it) }
        val limitsJson = json.get("limits")
        if (limitsJson != null && limitsJson.isJsonObject) {
            stored.limits = normalizeLimits(limitsJson.asJsonObject)
        }
        return stored
    }

    private fun writeFile(stored: Stored) {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
            gson.toJson(stored, writer)
        }
    }

    private fun defaultParallelism(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private fun defaultStored(): Stored {
        val stored = Stored()
        stored.parallelism = defaultParallelism()
        stored.limits = linkedMapOf("default" to LimitGroup())
        return stored
    }

    @Volatile
    private var loading: Boolean = false

    /**
     * 一个限额组。字段为 -1 时，半径改用 WorldEdit 配置，其余项目不封顶。
     *
     * @property maxChanges 一次编辑最多改变的方块数。
     * @property maxChecks 一次编辑里 `getBlock`、`getFullBlock` 和 `setBiome` 的合计上限。
     * @property maxFails 一次编辑里区域守卫最多拒绝的次数。
     * @property maxIterations 笔刷平滑、侵蚀一类循环的最多轮数。
     * @property maxEntities 一次编辑里 `createEntity` 的上限。删除实体不计入。
     * @property maxBlockStates 一次编辑最多写入的带 NBT 方块数。
     * @property maxRadius 命令半径上限。-1 表示改用 WorldEdit 的 `max-radius`。
     * @property maxBrushRadius 笔刷半径上限。-1 表示改用 WorldEdit 的 `max-brush-radius`。
     * @property maxExpressionMs 表达式最长运行时间，单位是毫秒。-1 表示不额外收紧。
     * @property maxHistoryMb 一名玩家的历史文件体积上限，单位是 MiB。-1 表示不限制。
     */
    class LimitGroup {
        var maxChanges: Long = 50_000_000
        var maxChecks: Long = 50_000_000
        var maxFails: Int = 50_000_000
        var maxIterations: Int = 1000
        var maxEntities: Int = 1337
        var maxBlockStates: Int = 1337
        var maxRadius: Int = -1
        var maxBrushRadius: Int = -1
        var maxExpressionMs: Int = 50
        var maxHistoryMb: Int = -1

        fun copy(): LimitGroup {
            val copy = LimitGroup()
            copy.maxChanges = maxChanges
            copy.maxChecks = maxChecks
            copy.maxFails = maxFails
            copy.maxIterations = maxIterations
            copy.maxEntities = maxEntities
            copy.maxBlockStates = maxBlockStates
            copy.maxRadius = maxRadius
            copy.maxBrushRadius = maxBrushRadius
            copy.maxExpressionMs = maxExpressionMs
            copy.maxHistoryMb = maxHistoryMb
            return copy
        }
    }

    /**
     * Gson 读写的配置体。无参构造的字段初值是 JSON 缺字段时的回退。
     * 缺文件时 [load] 另建一份，并把 `parallelism` 设成当前 CPU 数。
     */
    private class Stored {
        var parallelism: Int = 1
        var chunksPerTick: Int = 16
        var history: Boolean = true
        var historyRetentionDays: Int = 1
        var imageHosts: ArrayList<String> = arrayListOf("i.imgur.com")
        var imageMaxPixels: Int = 8_294_400
        var imageLoadTimeoutSeconds: Int = 5
        var webUrl: String = "https://schem.intellectualsites.com/fawe/"
        var arkitektonikaBackend: String = "https://api.schematic.cloud/"
        var arkitektonikaDownload: String = "https://schematic.cloud/download/{key}"
        var arkitektonikaDelete: String = "https://schematic.cloud/delete/{key}"
        var schematicDownloadHosts: ArrayList<String> = arrayListOf(
            "schematic.cloud",
            "paste.enginehub.org",
            "schem.intellectualsites.com",
        )
        var limits: LinkedHashMap<String, LimitGroup> = linkedMapOf("default" to LimitGroup())

        /**
         * 复制已经解析好的限额组。空白组名和 null 会被丢掉，并且保证存在 `default`。
         */
        fun sanitized(): Stored {
            val copy = Stored()
            copy.parallelism = parallelism.coerceAtLeast(1)
            copy.chunksPerTick = chunksPerTick.coerceAtLeast(1)
            copy.history = history
            copy.historyRetentionDays = historyRetentionDays.coerceAtLeast(1)
            copy.imageHosts = ArrayList(if (imageHosts.isEmpty()) listOf("i.imgur.com") else imageHosts)
            copy.imageMaxPixels = imageMaxPixels.coerceAtLeast(1)
            copy.imageLoadTimeoutSeconds = imageLoadTimeoutSeconds.coerceAtLeast(1)
            copy.webUrl = webUrl.ifBlank { "https://schem.intellectualsites.com/fawe/" }
            if (!copy.webUrl.endsWith("/")) {
                copy.webUrl += "/"
            }
            copy.arkitektonikaBackend = arkitektonikaBackend.ifBlank { "https://api.schematic.cloud/" }
            copy.arkitektonikaDownload = arkitektonikaDownload.ifBlank { "https://schematic.cloud/download/{key}" }
            copy.arkitektonikaDelete = arkitektonikaDelete.ifBlank { "https://schematic.cloud/delete/{key}" }
            copy.schematicDownloadHosts = ArrayList(
                if (schematicDownloadHosts.isEmpty()) {
                    listOf("schematic.cloud", "paste.enginehub.org", "schem.intellectualsites.com")
                } else {
                    schematicDownloadHosts
                },
            )
            copy.limits = LinkedHashMap()
            for ((name, group) in limits) {
                if (name.isNotBlank()) {
                    copy.limits[name] = group.copy()
                }
            }
            if (!copy.limits.containsKey("default")) {
                copy.limits["default"] = LimitGroup()
            }
            return copy
        }

        fun sameAs(other: Stored): Boolean {
            return parallelism == other.parallelism &&
                chunksPerTick == other.chunksPerTick &&
                history == other.history &&
                historyRetentionDays == other.historyRetentionDays &&
                imageHosts == other.imageHosts &&
                imageMaxPixels == other.imageMaxPixels &&
                imageLoadTimeoutSeconds == other.imageLoadTimeoutSeconds &&
                webUrl == other.webUrl &&
                arkitektonikaBackend == other.arkitektonikaBackend &&
                arkitektonikaDownload == other.arkitektonikaDownload &&
                arkitektonikaDelete == other.arkitektonikaDelete &&
                schematicDownloadHosts == other.schematicDownloadHosts &&
                limitsSame(limits, other.limits)
        }
    }

    private fun normalizeLimits(raw: JsonObject): LinkedHashMap<String, LimitGroup> {
        val result = LinkedHashMap<String, LimitGroup>()
        for (entry in raw.entrySet()) {
            if (entry.key.isBlank() || !entry.value.isJsonObject) {
                continue
            }
            result[entry.key] = readGroup(entry.value.asJsonObject)
        }
        if (!result.containsKey("default")) {
            result["default"] = LimitGroup()
        }
        return result
    }

    private fun JsonObject.optionalInt(name: String): Int? = number(name)?.asInt

    private fun JsonObject.optionalBoolean(name: String): Boolean? {
        val value = get(name) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) value.asBoolean else null
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
    }

    private fun JsonObject.optionalStringList(name: String): List<String>? {
        val value = get(name) ?: return null
        if (!value.isJsonArray) {
            return null
        }
        return value.asJsonArray.mapNotNull { element ->
            if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
        }
    }

    private fun JsonObject.number(name: String): JsonElement? {
        val value = get(name) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) value else null
    }

    private fun readGroup(json: JsonObject): LimitGroup {
        val group = LimitGroup()
        group.maxChanges = json.long("maxChanges", group.maxChanges)
        group.maxChecks = json.long("maxChecks", group.maxChecks)
        group.maxFails = json.int("maxFails", group.maxFails)
        group.maxIterations = json.int("maxIterations", group.maxIterations)
        group.maxEntities = json.int("maxEntities", group.maxEntities)
        group.maxBlockStates = json.int("maxBlockStates", group.maxBlockStates)
        group.maxRadius = json.int("maxRadius", group.maxRadius)
        group.maxBrushRadius = json.int("maxBrushRadius", group.maxBrushRadius)
        group.maxExpressionMs = json.int("maxExpressionMs", group.maxExpressionMs)
        group.maxHistoryMb = json.int("maxHistoryMb", group.maxHistoryMb)
        return group
    }

    private fun JsonObject.long(name: String, fallback: Long): Long {
        val value = get(name) ?: return fallback
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) value.asLong else fallback
    }

    private fun JsonObject.int(name: String, fallback: Int): Int {
        val value = get(name) ?: return fallback
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) value.asInt else fallback
    }

    private fun limitsSame(left: Map<String, LimitGroup>?, right: Map<String, LimitGroup>?): Boolean {
        val leftMap = left ?: emptyMap()
        val rightMap = right ?: emptyMap()
        if (leftMap.keys != rightMap.keys) {
            return false
        }
        for ((name, group) in leftMap) {
            val other = rightMap[name] ?: return false
            if (!group.sameNumbers(other)) {
                return false
            }
        }
        return true
    }

    private fun LimitGroup.sameNumbers(other: LimitGroup): Boolean {
        return maxChanges == other.maxChanges &&
            maxChecks == other.maxChecks &&
            maxFails == other.maxFails &&
            maxIterations == other.maxIterations &&
            maxEntities == other.maxEntities &&
            maxBlockStates == other.maxBlockStates &&
            maxRadius == other.maxRadius &&
            maxBrushRadius == other.maxBrushRadius &&
            maxExpressionMs == other.maxExpressionMs &&
            maxHistoryMb == other.maxHistoryMb
    }
}
