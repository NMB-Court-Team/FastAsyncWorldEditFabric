package net.astrorbits.fawe.mask

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.entity.Player
import com.sk89q.worldedit.extension.input.InputParseException
import com.sk89q.worldedit.extension.input.ParserContext
import com.sk89q.worldedit.extent.Extent
import com.sk89q.worldedit.function.mask.BlockTypeMask
import com.sk89q.worldedit.function.mask.ExistingBlockMask
import com.sk89q.worldedit.function.mask.Mask
import com.sk89q.worldedit.function.mask.MaskIntersection
import com.sk89q.worldedit.function.mask.Masks
import com.sk89q.worldedit.function.mask.OffsetMask
import com.sk89q.worldedit.internal.registry.InputParser
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.util.HandSide
import com.sk89q.worldedit.world.block.BlockTypes
import net.astrorbits.fawe.pattern.FaweNoise
import java.util.stream.Stream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 补上上游 `MaskFactory` 里多出来的掩码。
 *
 * 方括号参数和上游一样，例如 `#angle[0][1]`、`#simplex[5][40][60]`、`~[air]`、`{[0][5]}`。
 * 认不出的输入返回 null，交给后面的方块掩码解析。
 */
object FaweMaskParsers {
    private val suggestions = listOf(
        "#true", "#false", "#liquid", "#surface", "#wall", "#xaxis", "#yaxis", "#zaxis", "#hotbar",
        "#angle", "#simplex", "#offset", "#beside", "#extrema", "#roc", "#surfaceangle", "{", "~", "~2d",
    )

    /**
     * 创建要登记进 [com.sk89q.worldedit.extension.factory.MaskFactory] 的解析器。
     *
     * @param worldEdit 用来继续解析掩码参数里的子掩码。
     */
    @JvmStatic
    fun parser(worldEdit: WorldEdit): InputParser<Mask> = object : InputParser<Mask>(worldEdit) {
        override fun parseFromInput(input: String, context: ParserContext): Mask? {
            if (input.isEmpty()) {
                return null
            }
            val head = input[0]
            if (head != '#' && head != '/' && head != '\\' && head != '{' && head != '~' && head != '|') {
                return null
            }
            return parse(worldEdit, input, context)
        }

        override fun getSuggestions(input: String, context: ParserContext): Stream<String> {
            return suggestions.filter { it.startsWith(input) }.stream()
        }
    }

    private fun parse(worldEdit: WorldEdit, input: String, context: ParserContext): Mask? {
        return when {
            input == "#true" -> Masks.alwaysTrue()
            input == "#false" -> Mask { false }
            input == "#liquid" -> liquid(context)
            input == "#surface" -> surface(context)
            input == "#wall" -> wall(context)
            input == "#xaxis" -> axis { it.x() }
            input == "#yaxis" -> axis { it.y() }
            input == "#zaxis" -> axis { it.z() }
            input == "#hotbar" -> hotbar(context)
            input.startsWith("#simplex") -> simplex(brackets(input, "#simplex"))
            input.startsWith("#angle") || input.startsWith("/") || input.startsWith("\\") -> {
                val prefix = when {
                    input.startsWith("#angle") -> "#angle"
                    input.startsWith("/") -> "/"
                    else -> "\\"
                }
                angle(context, brackets(input, prefix), false)
            }
            input.startsWith("#surfaceangle") -> angle(context, brackets(input, "#surfaceangle"), true)
            input.startsWith("#extrema") -> extrema(context, brackets(input, "#extrema"))
            input.startsWith("#roc") -> roc(context, brackets(input, "#roc"))
            input.startsWith("#offset") -> offset(worldEdit, context, brackets(input, "#offset"))
            input.startsWith("#beside") || input.startsWith("|") -> {
                val prefix = if (input.startsWith("#beside")) "#beside" else "|"
                beside(worldEdit, context, brackets(input, prefix), false)
            }
            input.startsWith("~2d") || input.startsWith("adjacent2d") -> beside(worldEdit, context, brackets(input, if (input.startsWith("~2d")) "~2d" else "adjacent2d"), true)
            input.startsWith("~") -> adjacent(worldEdit, context, brackets(input, "~"))
            input.startsWith("{") -> radius(brackets(input, "{"))
            else -> null
        }
    }

    private fun brackets(input: String, prefix: String): List<String>? {
        if (!input.startsWith(prefix)) {
            return null
        }
        val rest = input.substring(prefix.length)
        if (rest.isEmpty()) {
            return emptyList()
        }
        if (!rest.startsWith("[")) {
            return null
        }
        val args = ArrayList<String>()
        var index = 0
        while (index < rest.length) {
            if (rest[index] != '[') {
                return null
            }
            val end = rest.indexOf(']', index + 1)
            if (end < 0) {
                return null
            }
            args.add(rest.substring(index + 1, end))
            index = end + 1
        }
        return args
    }

    private fun liquid(context: ParserContext): Mask {
        val extent = context.requireExtent()
        return Mask { position -> extent.getBlock(position).blockType.material.isLiquid }
    }

    private fun surface(context: ParserContext): Mask {
        val extent = context.requireExtent()
        return Mask { position ->
            val material = extent.getBlock(position).blockType.material
            material.isMovementBlocker && neighbors(position, false).any { neighbor ->
                !extent.getBlock(neighbor).blockType.material.isMovementBlocker
            }
        }
    }

    private fun wall(context: ParserContext): Mask {
        val extent = context.requireExtent()
        val existing = ExistingBlockMask(extent)
        return MaskIntersection(existing, { position ->
            val count = neighbors(position, true).count { neighbor ->
                extent.getBlock(neighbor).blockType.material.isAir
            }
            count in 1..8
        })
    }

    private fun axis(coordinate: (BlockVector3) -> Int): Mask {
        var locked = Int.MIN_VALUE
        return Mask { position ->
            if (locked == Int.MIN_VALUE) {
                locked = coordinate(position)
            }
            coordinate(position) == locked
        }
    }

    private fun hotbar(context: ParserContext): Mask {
        val actor = context.actor
        val type = if (actor is Player) {
            BlockTypes.get(actor.getItemInHand(HandSide.MAIN_HAND).type.id)
        } else {
            null
        }
        if (type == null) {
            return Mask { false }
        }
        return BlockTypeMask(context.requireExtent(), type)
    }

    private fun simplex(args: List<String>?): Mask? {
        if (args == null || args.size != 3) {
            return null
        }
        val scale = 1.0 / max(1.0, args[0].toDouble())
        val min = (args[1].toDouble() - 50.0) / 50.0
        val maxValue = (args[2].toDouble() - 50.0) / 50.0
        return Mask { position ->
            val value = FaweNoise.sample(position.x() * scale, position.y() * scale, position.z() * scale)
            value in min..maxValue
        }
    }

    private fun angle(context: ParserContext, args: List<String>?, surfaceOnly: Boolean): Mask? {
        if (args == null || args.size < 2) {
            return null
        }
        val degrees = args[0].endsWith("d")
        if (degrees != args[1].endsWith("d")) {
            throw InputParseException("Angle bounds must both use degrees or both use slopes")
        }
        var min = number(args[0])
        var maxValue = number(args[1])
        if (degrees) {
            min = kotlin.math.tan(min * Math.PI / 180.0)
            maxValue = kotlin.math.tan(maxValue * Math.PI / 180.0)
        }
        val distance = if (surfaceOnly && args.size > 2) args[2].toInt() else 1
        val overlay = args.drop(2).any { it == "-o" } || surfaceOnly
        val extent = context.requireExtent()
        val heights = HashMap<Long, Int>()
        return Mask { position ->
            val block = extent.getBlock(position)
            if (!block.blockType.material.isSolid && !block.blockType.material.isMovementBlocker) {
                return@Mask false
            }
            if (overlay) {
                val above = extent.getBlock(position.add(0, 1, 0))
                if (above.blockType.material.isMovementBlocker) {
                    return@Mask false
                }
            }
            val slope = slope(extent, heights, position.x(), position.z(), distance)
            if (maxValue >= kotlin.math.tan(Math.PI / 2)) slope >= min else slope in min..maxValue
        }
    }

    private fun extrema(context: ParserContext, args: List<String>?): Mask? {
        if (args == null || args.size < 2) {
            return null
        }
        val min = number(args[0])
        val maxValue = number(args[1])
        val extent = context.requireExtent()
        val heights = HashMap<Long, Int>()
        return Mask { position ->
            val center = columnTop(extent, heights, position.x(), position.z())
            val around = neighbors(position, true).map { columnTop(extent, heights, it.x(), it.z()) }
            val delta = max(abs(center - around.max()), abs(center - around.min()))
            delta.toDouble() in min..maxValue
        }
    }

    private fun roc(context: ParserContext, args: List<String>?): Mask? {
        val base = angle(context, args, false) ?: return null
        val extent = context.requireExtent()
        return Mask { position ->
            base.test(position) && !base.test(position.add(1, 0, 0))
        }.let { edge ->
            Mask { position ->
                edge.test(position) && extent.getBlock(position).blockType.material.isMovementBlocker
            }
        }
    }

    private fun offset(worldEdit: WorldEdit, context: ParserContext, args: List<String>?): Mask? {
        if (args == null || args.size != 4) {
            return null
        }
        val mask = worldEdit.maskFactory.parseFromInput(args[3], context)
        return OffsetMask(mask, BlockVector3.at(args[0].toInt(), args[1].toInt(), args[2].toInt()))
    }

    private fun adjacent(worldEdit: WorldEdit, context: ParserContext, args: List<String>?): Mask? {
        if (args.isNullOrEmpty()) {
            return null
        }
        val mask = worldEdit.maskFactory.parseFromInput(args[0], context)
        val min = if (args.size > 1) args[1].toInt() else 1
        val maxValue = if (args.size > 2) args[2].toInt() else 8
        return Mask { position ->
            val count = neighbors(position, false).count { mask.test(it) }
            count >= min && (maxValue >= 8 || count <= maxValue)
        }
    }

    private fun beside(worldEdit: WorldEdit, context: ParserContext, args: List<String>?, horizontal: Boolean): Mask? {
        if (args.isNullOrEmpty()) {
            return null
        }
        val mask = worldEdit.maskFactory.parseFromInput(args[0], context)
        val min = if (args.size > 1) args[1].toInt() else 1
        val maxValue = if (args.size > 2) args[2].toInt() else 8
        return Mask { position ->
            val count = neighbors(position, true).count { mask.test(it) }
            count in min..maxValue
        }
    }

    private fun radius(args: List<String>?): Mask? {
        if (args == null || args.size != 2) {
            return null
        }
        val min = args[0].toInt()
        val maxValue = args[1].toInt()
        var origin: BlockVector3? = null
        return Mask { position ->
            val start = origin ?: position.also { origin = it }
            val dx = start.x() - position.x()
            val dy = start.y() - position.y()
            val dz = start.z() - position.z()
            val distance = dx * dx + dy * dy + dz * dz
            distance in (min * min)..(maxValue * maxValue)
        }
    }

    private fun number(text: String): Double {
        val trimmed = if (text.endsWith("d")) text.dropLast(1) else text
        return trimmed.toDouble()
    }

    private fun neighbors(position: BlockVector3, horizontal: Boolean): List<BlockVector3> {
        val found = ArrayList<BlockVector3>(8)
        for (x in -1..1) {
            for (z in -1..1) {
                if (horizontal && x == 0 && z == 0) {
                    continue
                }
                if (horizontal) {
                    found.add(position.add(x, 0, z))
                }
            }
        }
        if (!horizontal) {
            found.add(position.add(1, 0, 0))
            found.add(position.add(-1, 0, 0))
            found.add(position.add(0, 1, 0))
            found.add(position.add(0, -1, 0))
            found.add(position.add(0, 0, 1))
            found.add(position.add(0, 0, -1))
        }
        return found
    }

    private fun slope(extent: Extent, heights: HashMap<Long, Int>, x: Int, z: Int, distance: Int): Double {
        fun height(px: Int, pz: Int) = columnTop(extent, heights, px, pz)
        val adjacent = max(
            abs(height(x + distance, z) - height(x - distance, z)) * 0.5,
            abs(height(x, z + distance) - height(x, z - distance)) * 0.5,
        )
        val diagonal = max(
            abs(height(x + distance, z + distance) - height(x - distance, z - distance)) / sqrt(8.0),
            abs(height(x - distance, z + distance) - height(x + distance, z - distance)) / sqrt(8.0),
        )
        return max(adjacent, diagonal)
    }

    private fun columnTop(extent: Extent, heights: HashMap<Long, Int>, x: Int, z: Int): Int {
        val key = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
        return heights.getOrPut(key) {
            var y = extent.maximumPoint.y()
            val minY = extent.minimumPoint.y()
            while (y > minY && !extent.getBlock(BlockVector3.at(x, y, z)).blockType.material.isMovementBlocker) {
                y--
            }
            y
        }
    }
}
