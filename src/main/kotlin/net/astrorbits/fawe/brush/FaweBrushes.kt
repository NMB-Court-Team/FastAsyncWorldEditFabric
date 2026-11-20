package net.astrorbits.fawe.brush

import com.google.gson.Gson
import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.LocalSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.command.tool.brush.Brush
import com.sk89q.worldedit.entity.Player
import com.sk89q.worldedit.event.platform.CommandEvent
import com.sk89q.worldedit.extension.input.ParserContext
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.function.pattern.Pattern
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector
import com.sk89q.worldedit.util.formatting.text.TextComponent
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent
import com.sk89q.worldedit.world.block.BlockTypes
import net.astrorbits.fawe.config.FaweConfig
import net.astrorbits.fawe.limit.FaweLimits
import net.astrorbits.fawe.net.FaweDownloads
import net.astrorbits.fawe.pattern.FaweNoise
import org.enginehub.piston.exception.StopExecutionException
import java.awt.image.BufferedImage
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 笔刷的绑定、磁盘保存，以及每种笔刷的放置规则。
 *
 * 命令类只负责参数。这里创建 [Brush]，编辑仍然进入当前的 [EditSession]。
 * `.jsgz` 是 gzip 压缩的 JSON，里面有类型、大小、图案文本、掩码文本和命令文本。
 */
object FaweBrushes {
    private val gson = Gson()
    private const val BRUSH_PACKAGE = "com.sk89q.worldedit.command.tool.brush."

    /**
     * 把一支笔刷绑到玩家手上的物品。
     *
     * @param kind 笔刷类型，和命令名相同，例如 `spline`。
     * @param patternText 图案文本。不需要图案的笔刷传 null。
     * @param maskText 掩码文本。没有掩码时传 null 或空字符串。
     * @param extra 命令、图片地址、原理图或迭代次数。没有时传 null。
     * @throws com.sk89q.worldedit.extension.input.InputParseException 图案或掩码文本无法解析时抛出。
     * @throws StopExecutionException 图片或原理图下载失败时抛出，消息是翻译键。未知笔刷类型同样抛出。
     */
    @JvmStatic
    fun bind(
        player: Player,
        session: LocalSession,
        kind: String,
        radius: Double,
        patternText: String?,
        maskText: String?,
        extra: String?,
    ) {
        val pattern = patternText?.let { parsePattern(player, session, it) }
        val mask = maskText?.takeIf { it.isNotBlank() }?.let { parseMask(player, session, it) }
        val inner = create(player, session, kind, extra, pattern)
        val described = DescribedBrush(kind, inner, patternText, maskText, extra)
        val tool = session.forceBrush(player.getItemInHand(com.sk89q.worldedit.util.HandSide.MAIN_HAND).type, described, "worldedit.brush.$kind")
        tool.size = radius
        tool.setFill(pattern ?: BlockTypes.AIR!!.defaultState)
        if (mask != null) {
            tool.mask = mask
        }
        player.printInfo(TranslatableComponent.of("fastasyncworldedit.brush.equipped", TextComponent.of(kind), TextComponent.of(radius)))
    }

    /**
     * 把当前物品上的笔刷写成 `.jsgz`。
     *
     * @param global 为 true 时写入 `brushes/global`，并要求 `worldedit.brush.save.other`。
     * 为 false 时写入该玩家自己的目录。
     */
    @JvmStatic
    fun save(player: Player, session: LocalSession, name: String, global: Boolean) {
        if (global && !player.hasPermission("worldedit.brush.save.other")) {
            throw StopExecutionException(TranslatableComponent.of("fastasyncworldedit.brush.global-denied"))
        }
        val tool = session.getBrush(player.getItemInHand(com.sk89q.worldedit.util.HandSide.MAIN_HAND).type)
            ?: throw StopExecutionException(TranslatableComponent.of("fastasyncworldedit.brush.none"))
        val described = tool.brush as? DescribedBrush
        val stored = StoredBrush(
            kind = described?.kind ?: (tool.brush.javaClass.name),
            size = tool.size,
            pattern = described?.patternText,
            mask = described?.maskText ?: "",
            extra = described?.extra,
        )
        val path = brushFile(player, name, global)
        Files.createDirectories(path.parent)
        GZIPOutputStream(Files.newOutputStream(path)).use { gzip ->
            OutputStreamWriter(gzip, StandardCharsets.UTF_8).use { writer -> gson.toJson(stored, writer) }
        }
        player.printInfo(TranslatableComponent.of("fastasyncworldedit.brush.saved", TextComponent.of(name)))
    }

    /**
     * 从 `.jsgz` 重新绑定笔刷。先找玩家自己的文件，没有再找全局文件。
     *
     * @param name 保存时用的名字，不带扩展名。
     */
    @JvmStatic
    fun load(player: Player, session: LocalSession, name: String) {
        val own = brushFile(player, name, false)
        val path = if (Files.exists(own)) own else brushFile(player, name, true)
        if (!Files.exists(path)) {
            throw StopExecutionException(TranslatableComponent.of("fastasyncworldedit.brush.missing", TextComponent.of(name)))
        }
        val stored = GZIPInputStream(Files.newInputStream(path)).use { gzip ->
            InputStreamReader(gzip, StandardCharsets.UTF_8).use { reader -> gson.fromJson(reader, StoredBrush::class.java) }
        }
        bind(player, session, stored.kind, stored.size, stored.pattern, stored.mask, stored.extra)
        player.printInfo(TranslatableComponent.of("fastasyncworldedit.brush.loaded", TextComponent.of(name)))
    }

    /**
     * 列出玩家自己的笔刷文件和全局笔刷文件。
     */
    @JvmStatic
    fun list(player: Player) {
        val own = names(brushFile(player, "unused", false).parent)
        val global = names(brushFile(player, "unused", true).parent)
        player.printInfo(TranslatableComponent.of("fastasyncworldedit.brush.list", TextComponent.of(own.size), TextComponent.of(global.size)))
        own.forEach { player.printInfo(TextComponent.of(it)) }
        global.forEach { player.printInfo(TextComponent.of("global:$it")) }
    }

    private fun names(folder: Path): List<String> {
        if (!Files.isDirectory(folder)) {
            return emptyList()
        }
        return Files.list(folder).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".jsgz") }
                .map { it.fileName.toString().removeSuffix(".jsgz") }
                .sorted()
                .toList()
        }
    }

    private fun brushFile(player: Player, name: String, global: Boolean): Path {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "brush" }
        val folder = if (global) "global" else player.uniqueId.toString()
        return FaweConfig.brushDirectory().resolve(folder).resolve("$safe.jsgz")
    }

    private fun parsePattern(player: Player, session: LocalSession, text: String): Pattern {
        return WorldEdit.getInstance().patternFactory.parseFromInput(text, context(player, session))
    }

    private fun parseMask(player: Player, session: LocalSession, text: String) =
        WorldEdit.getInstance().maskFactory.parseFromInput(text, context(player, session))

    private fun context(player: Player, session: LocalSession): ParserContext {
        val context = ParserContext()
        context.actor = player
        context.session = session
        context.world = player.world
        context.extent = player.world
        context.isRestricted = false
        return context
    }

    private fun create(player: Player, session: LocalSession, kind: String, extra: String?, pattern: Pattern?): Brush {
        if (kind.startsWith(BRUSH_PACKAGE)) {
            return Class.forName(kind).getDeclaredConstructor().newInstance() as Brush
        }
        return when (kind) {
            "blendball" -> BlendBrush(player, extra?.toIntOrNull() ?: 1)
            "erode" -> ErodeBrush(player, extra?.toIntOrNull() ?: 1)
            "pull" -> PullBrush(player)
            "circle" -> CircleBrush()
            "recursive" -> RecursiveBrush(player)
            "line" -> LineBrush(player)
            "spline", "surfacespline" -> SplineBrush(player, kind == "surfacespline")
            "sweep" -> SweepBrush(player)
            "catenary" -> CatenaryBrush(player)
            "rock" -> RockBrush()
            "shatter" -> ShatterBrush(player)
            "surface" -> SurfaceBrush()
            "scatter" -> ScatterBrush(player, false, null)
            "scattercommand" -> ScatterBrush(player, true, extra)
            "layer" -> LayerBrush(extra?.toIntOrNull() ?: 1)
            "height" -> HeightBrush()
            "cliff" -> CliffBrush()
            "flatten" -> FlattenBrush()
            "copypaste" -> CopyPasteBrush(player)
            "command" -> CommandBrush(player, session, extra.orEmpty())
            "image", "stencil" -> imageBrush(extra, kind == "image")
            "populateschematic" -> PopulateBrush(player, loadSchematic(player, extra), extra?.substringAfter('\n', "1")?.toIntOrNull() ?: 1)
            else -> throw StopExecutionException(TranslatableComponent.of("fastasyncworldedit.brush.unknown", TextComponent.of(kind)))
        }
    }

    private fun imageBrush(extra: String?, color: Boolean): Brush {
        val url = extra?.substringBefore('\n').orEmpty()
        val bytes = try {
            FaweDownloads.download(url, FaweConfig.imageHosts, FaweConfig.imageLoadTimeoutSeconds)
        } catch (rejected: FaweDownloads.Rejected) {
            throw StopExecutionException(TranslatableComponent.of(rejected.key))
        }
        val image = try {
            FaweDownloads.readImage(bytes, FaweConfig.imageMaxPixels)
        } catch (rejected: FaweDownloads.Rejected) {
            throw StopExecutionException(TranslatableComponent.of(rejected.key))
        }
        return ImageBrush(image, color)
    }

    private fun loadSchematic(player: Player, extra: String?): Clipboard {
        val source = extra?.substringBefore('\n').orEmpty()
        val bytes = if (source.startsWith("https://") || source.startsWith("url:")) {
            if (!player.hasPermission("worldedit.schematic.load.web")) {
                throw StopExecutionException(TranslatableComponent.of("fastasyncworldedit.schematic.web-denied"))
            }
            val url = if (source.startsWith("url:")) {
                FaweDownloads.webClipboardUri(source.removePrefix("url:")).toString()
            } else {
                source
            }
            try {
                FaweDownloads.download(url, FaweConfig.schematicDownloadHosts, 10)
            } catch (rejected: FaweDownloads.Rejected) {
                throw StopExecutionException(TranslatableComponent.of(rejected.key))
            }
        } else {
            val root = WorldEdit.getInstance().schematicsManager.root
            val direct = root.resolve(source).normalize()
            val file = if (direct.startsWith(root) && Files.exists(direct)) {
                direct
            } else {
                // 与上游 FAWE 一致：省略扩展名时自动尝试 .schem。
                val withSuffix = root.resolve("$source.schem").normalize()
                if (withSuffix.startsWith(root) && Files.exists(withSuffix)) {
                    withSuffix
                } else {
                    throw StopExecutionException(TranslatableComponent.of("fastasyncworldedit.brush.missing", TextComponent.of(source)))
                }
            }
            Files.readAllBytes(file)
        }
        return FaweDownloads.readClipboard(bytes, ".schem")
    }

    private class DescribedBrush(
        val kind: String,
        private val inner: Brush,
        val patternText: String?,
        val maskText: String?,
        val extra: String?,
    ) : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            inner.build(editSession, position, pattern, size)
        }
    }

    private data class StoredBrush(
        val kind: String = "",
        val size: Double = 1.0,
        val pattern: String? = null,
        val mask: String? = null,
        val extra: String? = null,
    )

    private fun visitSphere(actor: Player, center: BlockVector3, radius: Double, action: (BlockVector3) -> Unit) {
        val reach = radius.roundToInt().coerceAtLeast(0)
        val limit = radius * radius
        var iteration = 0
        for (x in -reach..reach) {
            for (y in -reach..reach) {
                for (z in -reach..reach) {
                    if (x * x + y * y + z * z <= limit) {
                        FaweLimits.checkIteration(actor, ++iteration)
                        action(center.add(x, y, z))
                    }
                }
            }
        }
    }

    private class BlendBrush(private val actor: Player, private val times: Int) : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            repeat(times.coerceIn(1, 8)) {
                val updates = HashMap<BlockVector3, Pattern>()
                visitSphere(actor, position, size) { pos ->
                    val counts = HashMap<String, Int>()
                    for (neighbor in faces(pos)) {
                        val id = editSession.getBlock(neighbor).asString
                        counts[id] = (counts[id] ?: 0) + 1
                    }
                    val winner = counts.maxByOrNull { it.value }?.key ?: return@visitSphere
                    updates[pos] = BlockTypes.get(winner.substringBefore('['))?.defaultState ?: editSession.getBlock(pos)
                }
                updates.forEach { (pos, block) -> editSession.setBlock(pos, block) }
            }
        }
    }

    private class ErodeBrush(private val actor: Player, private val times: Int) : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val fill = pattern
            repeat(times.coerceIn(1, 8)) {
                val updates = HashMap<BlockVector3, Pattern>()
                visitSphere(actor, position, size) { pos ->
                    val solid = editSession.getBlock(pos).blockType.material.isMovementBlocker
                    val solidNeighbors = faces(pos).count { editSession.getBlock(it).blockType.material.isMovementBlocker }
                    if (solid && solidNeighbors <= 2) {
                        updates[pos] = BlockTypes.AIR!!.defaultState
                    } else if (!solid && solidNeighbors >= 4) {
                        updates[pos] = fill
                    }
                }
                updates.forEach { (pos, block) -> editSession.setBlock(pos, block) }
            }
        }
    }

    private class PullBrush(private val actor: Player) : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val reach = size.roundToInt().coerceAtLeast(1)
            for (x in -reach..reach) {
                for (z in -reach..reach) {
                    val distance = sqrt((x * x + z * z).toDouble())
                    if (distance > size) {
                        continue
                    }
                    val lift = max(1, ((1.0 - distance / size) * size).roundToInt())
                    val columnX = position.x() + x
                    val columnZ = position.z() + z
                    for (step in lift downTo 1) {
                        FaweLimits.checkIteration(actor, step)
                        val from = editSession.getFullBlock(BlockVector3.at(columnX, position.y() - step, columnZ))
                        editSession.setBlock(BlockVector3.at(columnX, position.y() - step + lift, columnZ), from)
                    }
                }
            }
        }
    }

    private class CircleBrush : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val steps = max(8, (Math.PI * 2 * size).roundToInt())
            for (step in 0 until steps) {
                val angle = step * Math.PI * 2 / steps
                val point = position.add((cos(angle) * size).roundToInt(), 0, (sin(angle) * size).roundToInt())
                editSession.setBlock(point, pattern)
            }
        }
    }

    private class RecursiveBrush(private val actor: Player) : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val target = editSession.getBlock(position).blockType
            val queue = ArrayDeque<BlockVector3>()
            val seen = HashSet<BlockVector3>()
            queue.add(position)
            var iteration = 0
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (!seen.add(next) || next.distance(position) > size) {
                    continue
                }
                FaweLimits.checkIteration(actor, ++iteration)
                if (editSession.getBlock(next).blockType != target) {
                    continue
                }
                editSession.setBlock(next, pattern)
                queue.addAll(faces(next))
            }
        }
    }

    private class LineBrush(private val actor: Player) : Brush {
        private var start: BlockVector3? = null

        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val from = start
            if (from == null) {
                start = position
                actor.printInfo(TranslatableComponent.of("fastasyncworldedit.brush.point"))
                return
            }
            start = null
            editSession.drawLine(pattern, from, position, size, true)
        }
    }

    private class SplineBrush(private val actor: Player, private val onSurface: Boolean) : Brush {
        private val points = ArrayList<BlockVector3>()

        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            if (points.isNotEmpty() && points.last() == position) {
                draw(editSession, pattern, size)
                points.clear()
                return
            }
            points.add(if (onSurface) BlockVector3.at(position.x(), surfaceY(editSession, position), position.z()) else position)
            actor.printInfo(TranslatableComponent.of("fastasyncworldedit.brush.points", TextComponent.of(points.size)))
            if (points.size >= 8) {
                draw(editSession, pattern, size)
                points.clear()
            }
        }

        private fun draw(editSession: EditSession, pattern: Pattern, size: Double) {
            if (points.size < 2) {
                return
            }
            for (index in 0 until points.size - 1) {
                editSession.drawLine(pattern, points[index], points[index + 1], size, true)
            }
        }
    }

    private class SweepBrush(private val actor: Player) : Brush {
        private var start: BlockVector3? = null

        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val from = start
            if (from == null) {
                start = position
                actor.printInfo(TranslatableComponent.of("fastasyncworldedit.brush.point"))
                return
            }
            start = null
            val distance = max(1, from.distance(position).roundToInt())
            for (step in 0..distance) {
                FaweLimits.checkIteration(actor, step + 1)
                val point = BlockVector3.at(
                    from.x() + (position.x() - from.x()) * step / distance,
                    from.y() + (position.y() - from.y()) * step / distance,
                    from.z() + (position.z() - from.z()) * step / distance,
                )
                editSession.makeSphere(point, pattern, max(1.0, size / 2), true)
            }
        }
    }

    private class CatenaryBrush(private val actor: Player) : Brush {
        private var start: BlockVector3? = null

        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val from = start
            if (from == null) {
                start = position
                actor.printInfo(TranslatableComponent.of("fastasyncworldedit.brush.point"))
                return
            }
            start = null
            val distance = max(1, from.distance(position).roundToInt())
            val sag = max(1.0, distance / 4.0)
            for (step in 0..distance) {
                val t = step / distance.toDouble()
                val point = BlockVector3.at(
                    from.x() + ((position.x() - from.x()) * t).roundToInt(),
                    from.y() + ((position.y() - from.y()) * t).roundToInt() - (sag * 4 * t * (1 - t)).roundToInt(),
                    from.z() + ((position.z() - from.z()) * t).roundToInt(),
                )
                editSession.makeSphere(point, pattern, max(0.0, size), true)
            }
        }
    }

    private class RockBrush : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            repeat(4) { index ->
                val point = position.add(Random.nextInt(-size.roundToInt(), size.roundToInt() + 1), Random.nextInt(0, size.roundToInt() + 1), Random.nextInt(-size.roundToInt(), size.roundToInt() + 1))
                editSession.makeSphere(point, pattern, max(1.0, size / (index + 2)), true)
            }
        }
    }

    private class ShatterBrush(private val actor: Player) : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val normals = List(3) { BlockVector3.at(Random.nextInt(-2, 3), Random.nextInt(-2, 3), Random.nextInt(-2, 3)) }
            visitSphere(actor, position, size) { pos ->
                val offset = pos.subtract(position)
                if (normals.any { abs(it.x() * offset.x() + it.y() * offset.y() + it.z() * offset.z()) <= 1 }) {
                    editSession.setBlock(pos, BlockTypes.AIR!!.defaultState)
                }
            }
        }
    }

    private class SurfaceBrush : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val reach = size.roundToInt()
            for (x in -reach..reach) {
                for (z in -reach..reach) {
                    if (x * x + z * z > size * size) {
                        continue
                    }
                    val column = position.add(x, 0, z)
                    val y = surfaceY(editSession, column)
                    editSession.setBlock(BlockVector3.at(column.x(), y, column.z()), pattern)
                }
            }
        }
    }

    private class ScatterBrush(private val actor: Player, private val commands: Boolean, private val text: String?) : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val count = max(1, (size * size).roundToInt())
            val reach = size.roundToInt().coerceAtLeast(1)
            repeat(count) { index ->
                FaweLimits.checkIteration(actor, index + 1)
                val point = position.add(Random.nextInt(-reach, reach + 1), Random.nextInt(-reach, reach + 1), Random.nextInt(-reach, reach + 1))
                if (commands) {
                    runCommands(actor, text.orEmpty(), point, size)
                } else {
                    editSession.setBlock(point, pattern)
                }
            }
        }
    }

    private class LayerBrush(private val layers: Int) : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val reach = size.roundToInt()
            for (x in -reach..reach) {
                for (z in -reach..reach) {
                    if (x * x + z * z > size * size) {
                        continue
                    }
                    val column = position.add(x, 0, z)
                    val top = surfaceY(editSession, column)
                    for (layer in 0 until layers.coerceAtLeast(1)) {
                        editSession.setBlock(BlockVector3.at(column.x(), top - layer, column.z()), pattern)
                    }
                }
            }
        }
    }

    private class HeightBrush : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val reach = size.roundToInt().coerceAtLeast(1)
            for (x in -reach..reach) {
                for (z in -reach..reach) {
                    if (x * x + z * z > size * size) {
                        continue
                    }
                    val sample = FaweNoise.sample((position.x() + x) / 8.0, 0.0, (position.z() + z) / 8.0)
                    val y = position.y() + (sample * size).roundToInt()
                    editSession.setBlock(BlockVector3.at(position.x() + x, y, position.z() + z), pattern)
                }
            }
        }
    }

    private class CliffBrush : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val reach = size.roundToInt().coerceAtLeast(1)
            for (x in -reach..reach) {
                for (z in -reach..reach) {
                    val here = surfaceY(editSession, position.add(x, 0, z))
                    val beside = surfaceY(editSession, position.add(x + 1, 0, z))
                    if (abs(here - beside) < 2) {
                        continue
                    }
                    val low = minOf(here, beside)
                    val high = maxOf(here, beside)
                    for (y in low..high) {
                        editSession.setBlock(BlockVector3.at(position.x() + x, y, position.z() + z), pattern)
                    }
                }
            }
        }
    }

    private class FlattenBrush : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val reach = size.roundToInt().coerceAtLeast(1)
            val target = position.y()
            for (x in -reach..reach) {
                for (z in -reach..reach) {
                    if (x * x + z * z > size * size) {
                        continue
                    }
                    val columnX = position.x() + x
                    val columnZ = position.z() + z
                    val top = editSession.getHighestTerrainBlock(columnX, columnZ, editSession.minimumPoint.y(), editSession.maximumPoint.y())
                    for (y in minOf(target, top)..maxOf(target, top)) {
                        val block = if (y > target) BlockTypes.AIR!!.defaultState else pattern
                        editSession.setBlock(BlockVector3.at(columnX, y, columnZ), block)
                    }
                }
            }
        }
    }

    private class CopyPasteBrush(private val actor: Player) : Brush {
        private var copied: Map<BlockVector3, com.sk89q.worldedit.world.block.BaseBlock>? = null

        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val existing = copied
            if (existing == null) {
                val blocks = HashMap<BlockVector3, com.sk89q.worldedit.world.block.BaseBlock>()
                visitSphere(actor, position, size) { pos ->
                    blocks[pos.subtract(position)] = editSession.getFullBlock(pos)
                }
                copied = blocks
                actor.printInfo(TranslatableComponent.of("fastasyncworldedit.brush.copied", TextComponent.of(blocks.size)))
                return
            }
            var iteration = 0
            existing.forEach { (offset, block) ->
                FaweLimits.checkIteration(actor, ++iteration)
                editSession.setBlock(position.add(offset), block)
            }
        }
    }

    private class CommandBrush(private val actor: Player, private val session: LocalSession, private val commands: String) : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val world = actor.world
            val reach = size.roundToInt()
            if (world != null && reach > 0) {
                session.setRegionSelector(world, CuboidRegionSelector(world, position.subtract(reach, reach, reach), position.add(reach, reach, reach)))
            }
            runCommands(actor, commands, position, size)
        }
    }

    private class ImageBrush(private val image: BufferedImage, private val color: Boolean) : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val width = image.width
            val height = image.height
            val scale = (size * 2) / max(width, height).coerceAtLeast(1)
            for (px in 0 until width) {
                for (py in 0 until height) {
                    val rgb = image.getRGB(px, py)
                    val alpha = (rgb ushr 24) and 0xff
                    if (alpha < 16) {
                        continue
                    }
                    val point = BlockVector3.at(
                        position.x() + ((px - width / 2) * scale).roundToInt(),
                        position.y(),
                        position.z() + ((py - height / 2) * scale).roundToInt(),
                    )
                    if (color) {
                        val block = net.astrorbits.fawe.pattern.FawePatternParsers.coloredBlock((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff)
                        editSession.setBlock(point, block)
                    } else {
                        editSession.setBlock(point, pattern)
                    }
                }
            }
        }
    }

    private class PopulateBrush(private val actor: Player, private val clipboard: Clipboard, private val copies: Int) : Brush {
        override fun build(editSession: EditSession, position: BlockVector3, pattern: Pattern, size: Double) {
            val reach = size.roundToInt().coerceAtLeast(1)
            val origin = clipboard.origin
            repeat(copies.coerceIn(1, 64)) { copy ->
                val point = position.add(Random.nextInt(-reach, reach + 1), 0, Random.nextInt(-reach, reach + 1))
                var iteration = copy * 100000
                for (blockPos in clipboard.region) {
                    FaweLimits.checkIteration(actor, ++iteration)
                    val block = clipboard.getFullBlock(blockPos)
                    if (block.blockType.material.isAir) {
                        continue
                    }
                    editSession.setBlock(point.add(blockPos.subtract(origin)), block)
                }
            }
        }
    }

    private fun runCommands(actor: Player, commands: String, position: BlockVector3, size: Double) {
        val world = actor.world?.name ?: ""
        for (raw in commands.split(';')) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                continue
            }
            val replaced = trimmed.replace("{x}", position.x().toString())
                .replace("{y}", position.y().toString())
                .replace("{z}", position.z().toString())
                .replace("{world}", world)
                .replace("{size}", size.toString())
            // CommandEvent 会去掉恰好一个前导斜杠，命令名本身仍以 / 开头，所以这里保留两个斜杠。
            val text = when {
                replaced.startsWith("//") -> replaced
                replaced.startsWith("/") -> "/$replaced"
                else -> "//$replaced"
            }
            WorldEdit.getInstance().eventBus.post(CommandEvent(actor, text))
        }
    }

    private fun faces(position: BlockVector3): List<BlockVector3> = listOf(
        position.add(1, 0, 0), position.add(-1, 0, 0), position.add(0, 1, 0),
        position.add(0, -1, 0), position.add(0, 0, 1), position.add(0, 0, -1),
    )

    private fun surfaceY(editSession: EditSession, position: BlockVector3): Int {
        return editSession.getHighestTerrainBlock(position.x(), position.z(), editSession.minimumPoint.y(), editSession.maximumPoint.y())
    }
}
