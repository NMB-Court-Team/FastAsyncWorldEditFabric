package net.astrorbits.fawe.history

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.extension.input.ParserContext
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.extension.platform.Capability
import com.sk89q.worldedit.history.UndoContext
import com.sk89q.worldedit.history.change.BiomeChange
import com.sk89q.worldedit.history.change.BiomeChange3D
import com.sk89q.worldedit.history.change.BlockChange
import com.sk89q.worldedit.history.change.Change
import com.sk89q.worldedit.history.change.EntityCreate
import com.sk89q.worldedit.history.change.EntityRemove
import com.sk89q.worldedit.history.changeset.ChangeSet
import com.sk89q.worldedit.util.concurrency.LazyReference
import net.astrorbits.fawe.internal.FaweHistory
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.util.Location
import com.sk89q.worldedit.world.World
import com.sk89q.worldedit.world.biome.BiomeType
import com.sk89q.worldedit.world.block.BaseBlock
import com.sk89q.worldedit.world.entity.EntityType
import net.astrorbits.fawe.FastAsyncWorldEditMain
import net.astrorbits.fawe.config.FaweConfig
import net.astrorbits.fawe.limit.FaweLimits
import org.enginehub.linbus.format.snbt.LinStringIO
import org.enginehub.linbus.stream.LinBinaryIO
import org.enginehub.linbus.tree.LinCompoundTag
import org.enginehub.linbus.tree.LinRootEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * 把一次编辑的变更写进 `config/fastasyncworldedit/history/<世界>/<执行者>/<时间>-<序号>.hist`。
 * 世界这一段会先整理：字母、数字、减号、下划线和点保留，其他字符换成下划线；整理后为空时使用 `world`。
 *
 * 文件头固定 128 字节，后面是变长记录，末尾是记录偏移。内存里在写入期间保留偏移，关闭时写回文件。
 * 方块状态以不带 NBT 的文本保存，方块实体和实体的 NBT 单独用二进制 NBT 保存。
 * 没有变更的文件会在关闭时删除。
 */
object DiskHistories {
    const val HEADER_BYTES: Int = 128
    private const val MAGIC: Int = 0x46415748
    internal const val VERSION: Short = 2

    /**
     * 旧版本把方块实体和实体的 NBT 写成 SNBT 文本。
     * lin-bus 0.2.0 的 SNBT 读取器无法解析空 compound（`{}`），带物品的箱子等方块实体撤销不了，
     * 所以从版本 2 起改成二进制 NBT，旧文件仍按文本读取。
     */
    internal const val LEGACY_VERSION: Short = 1
    internal const val BLOCK: Int = 1
    internal const val BIOME: Int = 2
    internal const val ENTITY_CREATE: Int = 3
    internal const val ENTITY_REMOVE: Int = 4

    /**
     * 一条可以重放的历史文件。
     *
     * @property file 历史文件路径。
     * @property createdAt 创建时间，单位是毫秒。
     * @property actorId 执行者 UUID；控制台是 `console`。
     * @property worldName 世界名。
     * @property count 记录条数。
     * @property min 变更包围盒的最小点。没有方块记录时为原点。
     * @property max 变更包围盒的最大点。
     */
    data class Entry(
        val file: Path,
        val createdAt: Long,
        val actorId: String,
        val worldName: String,
        val count: Int,
        val min: BlockVector3,
        val max: BlockVector3,
    )

    /**
     * 打开这次会话要用的变更集。
     *
     * 历史关闭、或没有世界时，返回不记录任何内容的变更集。
     *
     * @param world 正在编辑的世界。
     * @param actor 发起编辑的对象。
     */
    @JvmStatic
    fun open(world: World?, actor: Actor?): ChangeSet {
        if (!FaweHistory.record() || world == null) {
            return EmptyChangeSet()
        }
        return DiskChangeSet(world, actor)
    }

    /**
     * 把还在缓冲区里的记录写完。空文件会被删除。
     *
     * @param changeSet 这次会话的变更集。其他实现会被忽略。
     */
    @JvmStatic
    fun flush(changeSet: ChangeSet) {
        if (changeSet is DiskChangeSet) {
            changeSet.flush()
        }
    }

    /**
     * 会话栈丢掉一次编辑时删除它的历史文件。
     *
     * @param session 被挤出撤销栈的会话。
     */
    @JvmStatic
    fun release(session: EditSession) {
        val changeSet = session.changeSet
        if (changeSet is DiskChangeSet) {
            changeSet.deleteFiles()
        }
    }

    /**
     * 删除超过保留天数的历史文件。
     */
    @JvmStatic
    fun purgeExpired() {
        val root = FaweConfig.historyDirectory()
        if (!root.exists()) {
            return
        }
        val cutoff = System.currentTimeMillis() - FaweConfig.historyRetentionDays.toLong() * 86_400_000L
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".hist") }.forEach { path ->
                val created = readCreatedAt(path)
                if (created != null && created < cutoff) {
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    /**
     * 按世界、执行者、时间和位置筛选历史文件。
     *
     * @param worldName 世界名。null 表示所有世界。
     * @param actorId 执行者 ID。null 表示所有执行者。
     * @param center 距离判断的中心。半径不大于 0 时忽略。
     * @param radius 包围盒到中心的最大距离。不大于 0 时不按位置过滤。
     * @param newerThanMillis 只保留这个时间之后创建的文件。0 表示不按时间过滤。
     */
    @JvmStatic
    fun find(
        worldName: String?,
        actorId: String?,
        center: BlockVector3?,
        radius: Int,
        newerThanMillis: Long,
    ): List<Entry> {
        val root = FaweConfig.historyDirectory()
        if (!root.exists()) {
            return emptyList()
        }
        val found = ArrayList<Entry>()
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".hist") }.forEach { path ->
                val entry = readEntry(path) ?: return@forEach
                if (worldName != null && entry.worldName != worldName) {
                    return@forEach
                }
                if (actorId != null && entry.actorId != actorId) {
                    return@forEach
                }
                if (newerThanMillis > 0 && entry.createdAt < newerThanMillis) {
                    return@forEach
                }
                if (radius > 0 && center != null && !near(entry, center, radius)) {
                    return@forEach
                }
                found.add(entry)
            }
        }
        found.sortByDescending { it.createdAt }
        return found
    }

    /**
     * 把一份历史按撤销或重做写回世界。
     *
     * @param actor 收到提示的执行者，同时作为新编辑会话的发起者。
     * @param entry 要重放的文件。
     * @param undo true 时按记录逆序撤销方块、群系和实体。false 时按记录顺序重做这些变更。
     * @return 找不到同名世界时返回 false。文件打不开或文件头读失败时抛出异常。
     */
    @JvmStatic
    fun apply(actor: Actor, entry: Entry, undo: Boolean): Boolean {
        val world = findWorld(entry.worldName) ?: return false
        val changeSet = DiskChangeSet(entry.file)
        val editSession = WorldEdit.getInstance().newEditSessionBuilder()
            .world(world)
            .actor(actor)
            .maxBlocks(FaweLimits.blockChangeLimit(actor, -1))
            .build()
        editSession.use { session ->
            val context = UndoContext()
            context.extent = session
            val iterator = if (undo) changeSet.backwardIterator() else changeSet.forwardIterator()
            while (iterator.hasNext()) {
                val change = iterator.next()
                if (undo) {
                    change.undo(context)
                } else {
                    change.redo(context)
                }
            }
        }
        return true
    }

    /**
     * 统计一份历史里每种方块状态出现的次数。键是写入后的方块字符串。
     *
     * @param entry 要统计的文件。
     */
    @JvmStatic
    fun distribution(entry: Entry): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        DiskChangeSet(entry.file).forwardIterator().forEach { change ->
            if (change is StoredBlockChange) {
                val name = change.currentState.substringBefore('{')
                counts[name] = (counts[name] ?: 0) + 1
            }
        }
        return counts
    }

    /**
     * 删除历史文件，并从当前会话栈里拿掉指向它的记录。
     *
     * @param session 玩家当前会话。为 null 时只删文件。
     * @param entry 要删除的文件。
     */
    @JvmStatic
    fun delete(session: com.sk89q.worldedit.LocalSession?, entry: Entry) {
        Files.deleteIfExists(entry.file)
        session?.dropHistoryFile(entry.file)
    }

    /**
     * 把 `20s`、`5m`、`1h`、`2d` 解析成毫秒。
     *
     * @param text 玩家输入的时间。空字符串和 `0` 返回 0。
     * @return 毫秒。无法解析时返回 null。
     */
    @JvmStatic
    fun parseDuration(text: String?): Long? {
        if (text.isNullOrBlank() || text == "0") {
            return 0L
        }
        val unit = text.last()
        val number = text.dropLast(1).toLongOrNull() ?: return null
        val millis = when (unit) {
            's' -> number * 1000
            'm' -> number * 60_000
            'h' -> number * 3_600_000
            'd' -> number * 86_400_000
            else -> return null
        }
        return millis
    }

    private fun findWorld(name: String): World? {
        val worlds = WorldEdit.getInstance().platformManager
            .queryCapability(Capability.GAME_HOOKS)
            .worlds
        return worlds.firstOrNull { it.name == name }
    }

    private fun near(entry: Entry, center: BlockVector3, radius: Int): Boolean {
        val x = center.x().coerceIn(entry.min.x(), entry.max.x())
        val y = center.y().coerceIn(entry.min.y(), entry.max.y())
        val z = center.z().coerceIn(entry.min.z(), entry.max.z())
        val dx = (x - center.x()).toLong()
        val dy = (y - center.y()).toLong()
        val dz = (z - center.z()).toLong()
        return dx * dx + dy * dy + dz * dz <= radius.toLong() * radius
    }

    private fun readCreatedAt(path: Path): Long? {
        return readHeader(path)?.createdAt
    }

    private fun readEntry(path: Path): Entry? {
        val header = readHeader(path) ?: return null
        return Entry(path, header.createdAt, header.actorId, header.worldName, header.count, header.min, header.max)
    }

    private fun readHeader(path: Path): Header? {
        if (!Files.isRegularFile(path) || Files.size(path) < HEADER_BYTES) {
            return null
        }
        RandomAccessFile(path.toFile(), "r").use { file ->
            val magic = file.readInt()
            val version = file.readShort()
            if (magic != MAGIC || (version != LEGACY_VERSION && version != VERSION)) {
                return null
            }
            val createdAt = file.readLong()
            val actorBytes = ByteArray(16)
            file.readFully(actorBytes)
            val minX = file.readInt()
            val minY = file.readInt()
            val minZ = file.readInt()
            val maxX = file.readInt()
            val maxY = file.readInt()
            val maxZ = file.readInt()
            val count = file.readInt()
            file.readLong()
            val worldBytes = ByteArray(62)
            file.readFully(worldBytes)
            val worldLength = worldBytes[0].toInt() and 0xFF
            val worldName = String(worldBytes, 1, worldLength.coerceAtMost(61), StandardCharsets.UTF_8)
            val actorId = actorIdFrom(actorBytes)
            return Header(
                createdAt,
                actorId,
                worldName,
                count,
                BlockVector3.at(minX, minY, minZ),
                BlockVector3.at(maxX, maxY, maxZ),
            )
        }
    }

    private fun actorIdFrom(bytes: ByteArray): String {
        if (bytes.all { it == 0.toByte() }) {
            return "console"
        }
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }

    private data class Header(
        val createdAt: Long,
        val actorId: String,
        val worldName: String,
        val count: Int,
        val min: BlockVector3,
        val max: BlockVector3,
    )
}

/**
 * 一份 `.hist` 变更集。
 *
 * 写入时把方块、群系和实体追加到文件。关闭后可以用同一路径重新打开并按顺序重放。
 */
class DiskChangeSet : ChangeSet {
    private val path: Path
    private val createdAt: Long
    private val version: Short
    private var recording = true
    private var count = 0
    private var minX = Int.MAX_VALUE
    private var minY = Int.MAX_VALUE
    private var minZ = Int.MAX_VALUE
    private var maxX = Int.MIN_VALUE
    private var maxY = Int.MIN_VALUE
    private var maxZ = Int.MIN_VALUE
    private val offsets = LongList()
    private var file: RandomAccessFile? = null
    private var flushed = false
    private var storedWorldName: String = ""
    private var storedActor: Actor? = null

    constructor(world: World, actor: Actor?) {
        createdAt = System.currentTimeMillis()
        version = DiskHistories.VERSION
        val worldFolder = DiskHistoriesFolder.safe(world.name)
        val actorFolder = actor?.uniqueId?.toString() ?: "console"
        val directory = FaweConfig.historyDirectory().resolve(worldFolder).resolve(actorFolder)
        Files.createDirectories(directory)
        var index = 0
        var candidate: Path
        do {
            candidate = directory.resolve("$createdAt-$index.hist")
            index++
        } while (candidate.exists())
        path = candidate
        storedWorldName = world.name
        storedActor = actor
        file = RandomAccessFile(path.toFile(), "rw")
        writeHeader(world.name, actor)
    }

    constructor(existing: Path) {
        path = existing
        val header = readExistingHeader()
        createdAt = header.first
        count = header.second
        version = header.third
        flushed = true
    }

    /**
     * @return 历史文件路径。文件还没关闭时也可以用它识别这次编辑。
     */
    fun file(): Path = path

    /**
     * 删除历史文件。已经删除时不做任何事。
     */
    fun deleteFiles() {
        file?.close()
        file = null
        Files.deleteIfExists(path)
    }

    /**
     * 把缓冲写进文件。没有记录时删除空文件。
     */
    fun flush() {
        val open = file ?: return
        if (flushed) {
            return
        }
        if (count == 0) {
            open.close()
            file = null
            Files.deleteIfExists(path)
            flushed = true
            return
        }
        val indexOffset = open.filePointer
        open.writeInt(offsets.size)
        for (index in 0 until offsets.size) {
            open.writeLong(offsets.get(index))
        }
        writeHeader(storedWorldName, storedActor, indexOffset)
        open.fd.sync()
        open.close()
        file = null
        flushed = true
        trimActorFolder()
    }

    override fun add(change: Change) {
        if (!recording) {
            return
        }
        val open = file ?: return
        val payload = encode(change) ?: return
        val offset = open.filePointer
        open.writeByte(payload.first)
        open.writeInt(payload.second.size)
        open.write(payload.second)
        offsets.add(offset)
        count++
    }

    override fun isRecordingChanges(): Boolean = recording

    override fun setRecordChanges(recordChanges: Boolean) {
        recording = recordChanges
    }

    override fun backwardIterator(): MutableIterator<Change> {
        val changes = loadChanges()
        Collections.reverse(changes)
        return changes.iterator()
    }

    override fun forwardIterator(): MutableIterator<Change> {
        return loadChanges().iterator()
    }

    override fun size(): Int = count

    private fun loadChanges(): MutableList<Change> {
        flush()
        if (!path.exists() || count == 0) {
            return ArrayList()
        }
        val changes = ArrayList<Change>(count)
        RandomAccessFile(path.toFile(), "r").use { open ->
            open.seek(54)
            val storedCount = open.readInt()
            val indexOffset = open.readLong()
            if (storedCount <= 0 || indexOffset <= 0) {
                return changes
            }
            open.seek(indexOffset)
            val indexCount = open.readInt()
            val positions = LongArray(indexCount)
            for (index in 0 until indexCount) {
                positions[index] = open.readLong()
            }
            for (position in positions) {
                open.seek(position)
                val type = open.readUnsignedByte()
                val length = open.readInt()
                val bytes = ByteArray(length)
                open.readFully(bytes)
                decode(type, bytes, version)?.let { changes.add(it) }
            }
        }
        return changes
    }

    private fun encode(change: Change): Pair<Int, ByteArray>? {
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        when (change) {
            is BlockChange -> {
                include(change.position)
                data.writeInt(change.position.x())
                data.writeInt(change.position.y())
                data.writeInt(change.position.z())
                data.writeBlock(change.previous)
                data.writeBlock(change.current)
                return DiskHistories.BLOCK to output.toByteArray()
            }
            is BiomeChange3D -> {
                include(change.position)
                data.writeInt(change.position.x())
                data.writeInt(change.position.y())
                data.writeInt(change.position.z())
                data.writeText(change.previous.id())
                data.writeText(change.current.id())
                return DiskHistories.BIOME to output.toByteArray()
            }
            is BiomeChange -> {
                val position = change.position.toBlockVector3()
                include(position)
                data.writeInt(position.x())
                data.writeInt(position.y())
                data.writeInt(position.z())
                data.writeText(change.previous.id())
                data.writeText(change.current.id())
                return DiskHistories.BIOME to output.toByteArray()
            }
            is EntityCreate -> {
                data.writeEntity(change)
                return DiskHistories.ENTITY_CREATE to output.toByteArray()
            }
            is EntityRemove -> {
                data.writeEntity(change)
                return DiskHistories.ENTITY_REMOVE to output.toByteArray()
            }
            else -> return null
        }
    }

    private fun DataOutputStream.writeEntity(change: Change) {
        val location: Location
        val type: String
        val nbt: LinCompoundTag?
        if (change is EntityCreate) {
            location = readLocation(change)
            val state = readState(change)
            type = state?.type?.id() ?: ""
            nbt = state?.nbt
        } else {
            val remove = change as EntityRemove
            location = readRemoveLocation(remove)
            val state = readRemoveState(remove)
            type = state?.type?.id() ?: ""
            nbt = state?.nbt
        }
        include(BlockVector3.at(location.x.toInt(), location.y.toInt(), location.z.toInt()))
        writeDouble(location.x)
        writeDouble(location.y)
        writeDouble(location.z)
        writeFloat(location.yaw)
        writeFloat(location.pitch)
        writeText(type)
        writeNbt(nbt)
    }

    private fun readLocation(change: EntityCreate): Location {
        val field = EntityCreate::class.java.getDeclaredField("location")
        field.isAccessible = true
        return field.get(change) as Location
    }

    private fun readState(change: EntityCreate): com.sk89q.worldedit.entity.BaseEntity? {
        val field = EntityCreate::class.java.getDeclaredField("state")
        field.isAccessible = true
        return field.get(change) as com.sk89q.worldedit.entity.BaseEntity?
    }

    private fun readRemoveLocation(change: EntityRemove): Location {
        val field = EntityRemove::class.java.getDeclaredField("location")
        field.isAccessible = true
        return field.get(change) as Location
    }

    private fun readRemoveState(change: EntityRemove): com.sk89q.worldedit.entity.BaseEntity? {
        val field = EntityRemove::class.java.getDeclaredField("state")
        field.isAccessible = true
        return field.get(change) as com.sk89q.worldedit.entity.BaseEntity?
    }

    private fun include(position: BlockVector3) {
        minX = minOf(minX, position.x())
        minY = minOf(minY, position.y())
        minZ = minOf(minZ, position.z())
        maxX = maxOf(maxX, position.x())
        maxY = maxOf(maxY, position.y())
        maxZ = maxOf(maxZ, position.z())
    }

    private fun writeHeader(worldName: String, actor: Actor?, indexOffset: Long = 0) {
        val open = file ?: return
        val marker = open.filePointer
        open.seek(0)
        open.writeInt(0x46415748)
        open.writeShort(version.toInt())
        open.writeLong(createdAt)
        val actorBytes = ByteArray(16)
        val uuid = actor?.uniqueId
        if (uuid != null) {
            val buffer = java.nio.ByteBuffer.wrap(actorBytes)
            buffer.putLong(uuid.mostSignificantBits)
            buffer.putLong(uuid.leastSignificantBits)
        }
        open.write(actorBytes)
        open.writeInt(if (count == 0) 0 else minX)
        open.writeInt(if (count == 0) 0 else minY)
        open.writeInt(if (count == 0) 0 else minZ)
        open.writeInt(if (count == 0) 0 else maxX)
        open.writeInt(if (count == 0) 0 else maxY)
        open.writeInt(if (count == 0) 0 else maxZ)
        open.writeInt(count)
        open.writeLong(indexOffset)
        val worldBytes = ByteArray(62)
        val encoded = worldName.toByteArray(StandardCharsets.UTF_8)
        val length = encoded.size.coerceAtMost(61)
        worldBytes[0] = length.toByte()
        encoded.copyInto(worldBytes, 1, 0, length)
        open.write(worldBytes)
        if (marker > DiskHistories.HEADER_BYTES) {
            open.seek(marker)
        } else {
            open.seek(DiskHistories.HEADER_BYTES.toLong())
        }
    }

    private fun readWorldName(): String {
        val open = file ?: return ""
        val marker = open.filePointer
        open.seek(66)
        val worldBytes = ByteArray(62)
        open.readFully(worldBytes)
        open.seek(marker)
        val length = worldBytes[0].toInt() and 0xFF
        return String(worldBytes, 1, length.coerceAtMost(61), StandardCharsets.UTF_8)
    }

    private fun readExistingHeader(): Triple<Long, Int, Short> {
        RandomAccessFile(path.toFile(), "r").use { open ->
            open.seek(4)
            val version = open.readShort()
            open.seek(6)
            val created = open.readLong()
            open.seek(54)
            val stored = open.readInt()
            return Triple(created, stored, version)
        }
    }

    private fun trimActorFolder() {
        val capMb = FaweLimits.resolve(storedActor).maxHistoryMb
        if (capMb < 0) {
            return
        }
        val folder = path.parent ?: return
        val files = Files.list(folder).use { stream ->
            stream.filter { it.toString().endsWith(".hist") }.sorted().toList()
        }
        var total = files.sumOf { Files.size(it) }
        val cap = capMb.toLong() * 1024L * 1024L
        for (old in files) {
            if (total <= cap || old == path) {
                break
            }
            val size = Files.size(old)
            Files.deleteIfExists(old)
            total -= size
        }
    }
}

private class EmptyChangeSet : ChangeSet {
    override fun add(change: Change) {
    }

    override fun isRecordingChanges(): Boolean = false

    override fun setRecordChanges(recordChanges: Boolean) {
    }

    override fun backwardIterator(): MutableIterator<Change> = Collections.emptyIterator()

    override fun forwardIterator(): MutableIterator<Change> = Collections.emptyIterator()

    override fun size(): Int = 0
}

private class LongList {
    private var data = LongArray(256)
    var size: Int = 0
        private set

    fun add(value: Long) {
        if (size == data.size) {
            data = data.copyOf(data.size * 2)
        }
        data[size++] = value
    }

    fun get(index: Int): Long = data[index]
}

internal class StoredBlockChange(
    private val position: BlockVector3,
    private val previous: StoredBlock,
    private val current: StoredBlock,
    private val legacyText: Boolean,
) : Change {
    /**
     * 写入后的方块状态文本，不含 NBT。`distr` 用它统计。
     */
    val currentState: String
        get() = current.state

    override fun undo(context: UndoContext) {
        val extent = context.extent ?: return
        extent.setBlock(position, previous.toBaseBlock(extent, legacyText))
    }

    override fun redo(context: UndoContext) {
        val extent = context.extent ?: return
        extent.setBlock(position, current.toBaseBlock(extent, legacyText))
    }
}

/**
 * 从历史文件读出的方块：不带 NBT 的状态文本，可以附带单独的二进制 NBT。
 *
 * @property state 方块状态文本，不包含 NBT。
 * @property nbt 二进制 NBT；没有方块实体数据时为 null。
 */
internal class StoredBlock(
    val state: String,
    private val nbt: ByteArray?,
) {
    /**
     * 重新构造要放回世界的方块。
     *
     * @param extent 解析时使用的范围。
     * @param legacyText 旧文件里 [state] 同时包含 SNBT 形式的 NBT，需要整体交给方块解析器。
     */
    fun toBaseBlock(extent: com.sk89q.worldedit.extent.Extent, legacyText: Boolean): BaseBlock {
        if (legacyText) {
            return parseBlock(state, extent)
        }
        val block = parseBlock(state, extent)
        val tag = nbt?.let(::decodeNbt) ?: return block
        return block.toBaseBlock(LazyReference.computed(tag))
    }
}

private class StoredBiomeChange(
    private val position: BlockVector3,
    private val previous: String,
    private val current: String,
) : Change {
    override fun undo(context: UndoContext) {
        val biome = BiomeType.REGISTRY.get(previous) ?: return
        context.extent?.setBiome(position, biome)
    }

    override fun redo(context: UndoContext) {
        val biome = BiomeType.REGISTRY.get(current) ?: return
        context.extent?.setBiome(position, biome)
    }
}

private class StoredEntityChange(
    private val create: Boolean,
    private val x: Double,
    private val y: Double,
    private val z: Double,
    private val yaw: Float,
    private val pitch: Float,
    private val typeId: String,
    private val nbt: ByteArray?,
    private val legacyNbt: String?,
) : Change {
    override fun undo(context: UndoContext) {
        if (create) {
            removeNear(context)
        } else {
            spawn(context)
        }
    }

    override fun redo(context: UndoContext) {
        if (create) {
            spawn(context)
        } else {
            removeNear(context)
        }
    }

    private fun spawn(context: UndoContext) {
        val extent = context.extent ?: return
        val type = EntityType.REGISTRY.get(typeId) ?: return
        val entity = com.sk89q.worldedit.entity.BaseEntity(type)
        val tag = if (legacyNbt != null) {
            if (legacyNbt.isEmpty()) null else LinStringIO.readFromStringUsing(legacyNbt, LinCompoundTag::readFrom)
        } else {
            nbt?.let(::decodeNbt)
        }
        if (tag != null) {
            entity.setNbtReference(LazyReference.computed(tag))
        }
        extent.createEntity(Location(extent, x, y, z, yaw, pitch), entity)
    }

    private fun removeNear(context: UndoContext) {
        val extent = context.extent ?: return
        val position = BlockVector3.at(x.toInt(), y.toInt(), z.toInt())
        val region = CuboidRegion(position.subtract(1, 1, 1), position.add(1, 1, 1))
        for (entity in extent.getEntities(region)) {
            val state = entity.state ?: continue
            if (state.type.id() == typeId) {
                entity.remove()
                return
            }
        }
    }
}

/**
 * 按记录类型和文件版本读出一条变更。
 *
 * 版本 1 的方块和实体 NBT 是 SNBT 文本，版本 2 起改成二进制 NBT。
 */
private fun decode(type: Int, bytes: ByteArray, version: Short): Change? {
    val data = DataInputStream(ByteArrayInputStream(bytes))
    val legacy = version < DiskHistories.VERSION
    return when (type) {
        DiskHistories.BLOCK -> {
            val position = BlockVector3.at(data.readInt(), data.readInt(), data.readInt())
            if (legacy) {
                StoredBlockChange(position, StoredBlock(data.readText(), null), StoredBlock(data.readText(), null), true)
            } else {
                StoredBlockChange(position, data.readBlock(), data.readBlock(), false)
            }
        }
        DiskHistories.BIOME -> StoredBiomeChange(
            BlockVector3.at(data.readInt(), data.readInt(), data.readInt()),
            data.readText(),
            data.readText(),
        )
        DiskHistories.ENTITY_CREATE, DiskHistories.ENTITY_REMOVE -> {
            val create = type == DiskHistories.ENTITY_CREATE
            val x = data.readDouble()
            val y = data.readDouble()
            val z = data.readDouble()
            val yaw = data.readFloat()
            val pitch = data.readFloat()
            val typeId = data.readText()
            if (legacy) {
                StoredEntityChange(create, x, y, z, yaw, pitch, typeId, null, data.readText())
            } else {
                StoredEntityChange(create, x, y, z, yaw, pitch, typeId, data.readNbtBytes(), null)
            }
        }
        else -> null
    }
}

private fun parseBlock(text: String, extent: com.sk89q.worldedit.extent.Extent): BaseBlock {
    val context = ParserContext()
    context.extent = extent
    context.setRestricted(false)
    return WorldEdit.getInstance().blockFactory.parseFromInput(text, context)
}

private fun DataOutputStream.writeText(text: String) {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readText(): String {
    val length = readInt()
    val bytes = ByteArray(length)
    readFully(bytes)
    return String(bytes, StandardCharsets.UTF_8)
}

private fun DataOutputStream.writeBlock(block: BaseBlock) {
    writeText(block.toImmutableState().asString)
    writeNbt(block.getNbt())
}

private fun DataOutputStream.writeNbt(tag: LinCompoundTag?) {
    if (tag == null) {
        writeBoolean(false)
        return
    }
    val bytes = LinRootEntry("", tag).writeToArray()
    writeBoolean(true)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readBlock(): StoredBlock = StoredBlock(readText(), readNbtBytes())

private fun DataInputStream.readNbtBytes(): ByteArray? {
    if (!readBoolean()) {
        return null
    }
    val length = readInt()
    val bytes = ByteArray(length)
    readFully(bytes)
    return bytes
}

private fun decodeNbt(bytes: ByteArray): LinCompoundTag? {
    return try {
        LinBinaryIO.readUsing(DataInputStream(ByteArrayInputStream(bytes)), LinRootEntry::readFrom).value()
    } catch (e: Exception) {
        FastAsyncWorldEditMain.LOGGER.warn("Failed to read NBT data from history file", e)
        null
    }
}

private object DiskHistoriesFolder {
    fun safe(name: String): String {
        val cleaned = name.map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_' || char == '.') char else '_'
        }.joinToString("")
        return cleaned.ifBlank { "world" }
    }
}
