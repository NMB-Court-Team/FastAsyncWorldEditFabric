package net.astrorbits.fawe.pattern

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.entity.Player
import com.sk89q.worldedit.extension.input.InputParseException
import com.sk89q.worldedit.extension.input.ParserContext
import com.sk89q.worldedit.extent.Extent
import com.sk89q.worldedit.function.pattern.AbstractPattern
import com.sk89q.worldedit.function.pattern.Pattern
import com.sk89q.worldedit.internal.block.BlockStateIdAccess
import com.sk89q.worldedit.internal.expression.Expression
import com.sk89q.worldedit.internal.registry.InputParser
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.math.Vector3
import com.sk89q.worldedit.regions.shape.WorldEditExpressionEnvironment
import com.sk89q.worldedit.util.HandSide
import com.sk89q.worldedit.util.formatting.text.TextComponent
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent
import com.sk89q.worldedit.world.biome.BiomeType
import com.sk89q.worldedit.world.block.BaseBlock
import com.sk89q.worldedit.world.block.BlockState
import com.sk89q.worldedit.world.block.BlockTypes
import java.util.stream.Stream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 平滑的三维梯度噪声，取值大约在 -1 到 1。
 *
 * `#simplex` 掩码和图案都用这一份采样，同一坐标每次结果相同。
 */
object FaweNoise {
    /**
     * @param x 采样坐标，调用方先乘过缩放。
     * @param y 采样坐标。
     * @param z 采样坐标。
     * @return 大约在 -1 到 1 之间的噪声。
     */
    @JvmStatic
    fun sample(x: Double, y: Double, z: Double): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val z0 = floor(z).toInt()
        val xf = x - x0
        val yf = y - y0
        val zf = z - z0
        val u = fade(xf)
        val v = fade(yf)
        val w = fade(zf)
        return lerp(
            lerp(
                lerp(lattice(x0, y0, z0), lattice(x0 + 1, y0, z0), u),
                lerp(lattice(x0, y0 + 1, z0), lattice(x0 + 1, y0 + 1, z0), u),
                v,
            ),
            lerp(
                lerp(lattice(x0, y0, z0 + 1), lattice(x0 + 1, y0, z0 + 1), u),
                lerp(lattice(x0, y0 + 1, z0 + 1), lattice(x0 + 1, y0 + 1, z0 + 1), u),
                v,
            ),
            w,
        )
    }

    private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    private fun lattice(x: Int, y: Int, z: Int): Double {
        var n = x * 374761393 + y * 668265263 + z * 1274126177
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xffff) / 32767.5 - 1.0
    }
}

/**
 * 补上上游 `PatternFactory` 里多出来的图案。
 *
 * 参数放在方括号里，例如 `#simplex[5][stone,dirt]`、`#offset[stone][0][1][0]`、`=x`。
 * 认不出的输入返回 null，交给后面的方块图案解析。
 */
object FawePatternParsers {
    private val prefixes = listOf(
        "#simplex", "#perlin", "#voronoi", "#ridged", "#anglecolor", "#anglecolour", "#averagecolor", "#averagecolour",
        "#biome", "#buffer2d", "#buffer", "#color", "#colour", "#darken", "#desaturate", "#existing", "#lighten",
        "#linear2d", "#l2d", "#linear3d", "#l3d", "#linear", "#l", "#mask", "#!x", "#nx", "#nox", "#!y", "#ny", "#noy",
        "#!z", "#nz", "#noz", "#offset", "#spread", "#randomoffset", "#relative", "#rel", "#r", "#~", "#fullcopy",
        "#solidspread", "#surfacespread", "#typeswap", "#ts", "#swaptype", "#saturate", "#hotbar",
    )

    /**
     * 创建要登记进 [com.sk89q.worldedit.extension.factory.PatternFactory] 的解析器。
     *
     * @param worldEdit 用来解析图案参数里的子图案和子掩码。
     */
    @JvmStatic
    fun parser(worldEdit: WorldEdit): InputParser<Pattern> = object : InputParser<Pattern>(worldEdit) {
        override fun parseFromInput(input: String, context: ParserContext): Pattern? {
            if (input.isEmpty()) {
                return null
            }
            val head = input[0]
            if (head != '#' && head != '$' && head != '=') {
                return null
            }
            return parse(worldEdit, input, context)
        }

        override fun getSuggestions(input: String, context: ParserContext): Stream<String> {
            return prefixes.filter { it.startsWith(input) }.stream()
        }
    }

    private fun parse(worldEdit: WorldEdit, input: String, context: ParserContext): Pattern? {
        val extent = context.extent
        return when {
            input == "#existing" -> existing(extent)
            input == "#darken" -> recolor(extent, -1)
            input == "#lighten" -> recolor(extent, 1)
            input == "#hotbar" -> hotbar(context)
            input.startsWith("=") && input.length > 1 -> expression(input.substring(1), extent)
            input.startsWith("$") -> biome(input.substring(1), extent)
            input.startsWith("#biome") -> biome(only(brackets(input, "#biome")), extent)
            input.startsWith("#simplex") -> noise(worldEdit, context, brackets(input, "#simplex"), 1)
            input.startsWith("#perlin") -> noise(worldEdit, context, brackets(input, "#perlin"), 2)
            input.startsWith("#voronoi") -> noise(worldEdit, context, brackets(input, "#voronoi"), 3)
            input.startsWith("#ridged") -> noise(worldEdit, context, brackets(input, "#ridged"), 4)
            input.startsWith("#anglecolor") || input.startsWith("#anglecolour") -> angleColor(extent, brackets(input, if (input.startsWith("#anglecolor")) "#anglecolor" else "#anglecolour"))
            input.startsWith("#averagecolor") || input.startsWith("#averagecolour") -> averageColor(extent, brackets(input, if (input.startsWith("#averagecolor")) "#averagecolor" else "#averagecolour"))
            input.startsWith("#buffer2d") -> buffered(worldEdit, context, brackets(input, "#buffer2d"), true)
            input.startsWith("#buffer") -> buffered(worldEdit, context, brackets(input, "#buffer"), false)
            input.startsWith("#color") || input.startsWith("#colour") -> color(brackets(input, if (input.startsWith("#color")) "#color" else "#colour"))
            input.startsWith("#desaturate") -> saturate(extent, brackets(input, "#desaturate"), false)
            input.startsWith("#saturate") -> saturate(extent, brackets(input, "#saturate"), true)
            input.startsWith("#linear2d") || input.startsWith("#l2d") -> linear(worldEdit, context, brackets(input, if (input.startsWith("#linear2d")) "#linear2d" else "#l2d"), 2)
            input.startsWith("#linear3d") || input.startsWith("#l3d") -> linear(worldEdit, context, brackets(input, if (input.startsWith("#linear3d")) "#linear3d" else "#l3d"), 3)
            input.startsWith("#linear") || input.startsWith("#l") -> linear(worldEdit, context, brackets(input, if (input.startsWith("#linear")) "#linear" else "#l"), 1)
            input.startsWith("#mask") -> masked(worldEdit, context, brackets(input, "#mask"))
            input.startsWith("#!x") || input.startsWith("#nx") || input.startsWith("#nox") -> locked(worldEdit, context, input, 0)
            input.startsWith("#!y") || input.startsWith("#ny") || input.startsWith("#noy") -> locked(worldEdit, context, input, 1)
            input.startsWith("#!z") || input.startsWith("#nz") || input.startsWith("#noz") -> locked(worldEdit, context, input, 2)
            input.startsWith("#offset") -> offset(worldEdit, context, brackets(input, "#offset"), false, false, false)
            input.startsWith("#solidspread") -> offset(worldEdit, context, brackets(input, "#solidspread"), true, false, true)
            input.startsWith("#surfacespread") -> offset(worldEdit, context, brackets(input, "#surfacespread"), false, true, true)
            input.startsWith("#spread") || input.startsWith("#randomoffset") -> offset(worldEdit, context, brackets(input, if (input.startsWith("#spread")) "#spread" else "#randomoffset"), false, false, true)
            input.startsWith("#relative") || input.startsWith("#rel") || input.startsWith("#r") || input.startsWith("#~") -> relative(worldEdit, context, input)
            input.startsWith("#fullcopy") -> fullCopy(context, brackets(input, "#fullcopy"))
            input.startsWith("#typeswap") || input.startsWith("#ts") || input.startsWith("#swaptype") -> typeSwap(extent, brackets(input, prefixOf(input, "#typeswap", "#swaptype", "#ts")))
            else -> null
        }
    }

    private fun brackets(input: String, prefix: String): List<String>? {
        if (!input.startsWith(prefix)) {
            return null
        }
        val rest = input.substring(prefix.length)
        if (!rest.startsWith("[")) {
            return if (rest.isEmpty()) emptyList() else null
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

    private fun prefixOf(input: String, vararg prefixes: String): String = prefixes.first { input.startsWith(it) }

    private fun only(args: List<String>?): String = args?.firstOrNull().orEmpty()

    private fun childPatterns(worldEdit: WorldEdit, context: ParserContext, text: String): List<Pattern> {
        return text.split(",").filter { it.isNotBlank() }.map { worldEdit.patternFactory.parseFromInput(it.trim(), context) }
    }

    private fun existing(extent: Extent?): Pattern {
        val source = extent ?: throw InputParseException(
            TranslatableComponent.of("fastasyncworldedit.pattern.existing.extent-required"),
        )
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock = source.getFullBlock(position)
        }
    }

    private fun hotbar(context: ParserContext): Pattern {
        val actor = context.actor
        val type = if (actor is Player) BlockTypes.get(actor.getItemInHand(HandSide.MAIN_HAND).type.id) else null
        val block = type?.defaultState?.toBaseBlock() ?: BlockTypes.AIR!!.defaultState.toBaseBlock()
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock = block
        }
    }

    private fun expression(body: String, extent: Extent?): Pattern {
        val compiled = Expression.compile(body, "x", "y", "z")
        if (extent != null) {
            compiled.setEnvironment(WorldEditExpressionEnvironment(extent, Vector3.ONE, Vector3.ZERO))
        }
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock {
                val value = compiled.evaluate(position.x().toDouble(), position.y().toDouble(), position.z().toDouble())
                val state = BlockStateIdAccess.getBlockStateById(value.toInt()) ?: BlockTypes.AIR!!.defaultState
                return state.toBaseBlock()
            }
        }
    }

    private fun biome(name: String, extent: Extent?): Pattern {
        val biome = BiomeType.REGISTRY.get(if (name.contains(':')) name else "minecraft:$name")
            ?: throw InputParseException(
                TranslatableComponent.of("fastasyncworldedit.pattern.biome.unknown", TextComponent.of(name)),
            )
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock {
                extent?.setBiome(position, biome)
                return extent?.getFullBlock(position) ?: BlockTypes.AIR!!.defaultState.toBaseBlock()
            }
        }
    }

    private fun noise(worldEdit: WorldEdit, context: ParserContext, args: List<String>?, salt: Int): Pattern? {
        if (args == null || args.size != 2) {
            return null
        }
        val scale = 1.0 / max(1.0, args[0].toDouble())
        val patterns = childPatterns(worldEdit, context, args[1])
        if (patterns.isEmpty()) {
            throw InputParseException(TranslatableComponent.of("fastasyncworldedit.pattern.noise.pattern-required"))
        }
        if (patterns.size == 1) {
            return patterns[0]
        }
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock {
                val sample = FaweNoise.sample(position.x() * scale + salt, position.y() * scale, position.z() * scale)
                val index = min(patterns.lastIndex, ((sample + 1.0) / 2.0 * patterns.size).toInt())
                return patterns[index].applyBlock(position)
            }
        }
    }

    private fun angleColor(extent: Extent?, args: List<String>?): Pattern? {
        if (extent == null || args == null) {
            return null
        }
        val distance = args.firstOrNull()?.toIntOrNull() ?: 1
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock {
                val left = extent.getBlock(position.add(-distance, 0, 0)).blockType.material.isMovementBlocker
                val right = extent.getBlock(position.add(distance, 0, 0)).blockType.material.isMovementBlocker
                val index = if (left == right) palette.size / 2 else if (left) 1 else palette.lastIndex
                return palette[index]
            }
        }
    }

    private fun averageColor(extent: Extent?, args: List<String>?): Pattern? {
        if (args == null || args.size < 3) {
            return null
        }
        val red = args[0].toInt()
        val green = args[1].toInt()
        val blue = args[2].toInt()
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock {
                val current = colorOf(extent?.getBlock(position))
                return nearest(current[0] / 2 + red / 2, current[1] / 2 + green / 2, current[2] / 2 + blue / 2)
            }
        }
    }

    private fun color(args: List<String>?): Pattern? {
        if (args == null || args.size < 3) {
            return null
        }
        val block = nearest(args[0].toInt(), args[1].toInt(), args[2].toInt())
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock = block
        }
    }

    private fun saturate(extent: Extent?, args: List<String>?, towardColor: Boolean): Pattern? {
        val percent = (args?.firstOrNull()?.toIntOrNull() ?: 100).coerceIn(0, 100)
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock {
                val state = extent?.getBlock(position) ?: return BlockTypes.AIR!!.defaultState.toBaseBlock()
                val current = colorOf(state)
                val average = (current[0] + current[1] + current[2]) / 3
                val factor = percent / 100.0
                val next = IntArray(3) { channel ->
                    val target = if (towardColor) current[channel] else average
                    val gray = if (towardColor) average else current[channel]
                    (gray + (target - gray) * factor).toInt()
                }
                return nearest(next[0], next[1], next[2])
            }
        }
    }

    private fun recolor(extent: Extent?, direction: Int): Pattern {
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock {
                val state = extent?.getBlock(position) ?: return BlockTypes.AIR!!.defaultState.toBaseBlock()
                val current = colorOf(state)
                val brightness = current[0] + current[1] + current[2] + direction * 80
                return nearest(brightness / 3, brightness / 3, brightness / 3)
            }
        }
    }

    private fun buffered(worldEdit: WorldEdit, context: ParserContext, args: List<String>?, flat: Boolean): Pattern? {
        if (args.isNullOrEmpty()) {
            return null
        }
        val inner = worldEdit.patternFactory.parseFromInput(args[0], context)
        val cache = HashMap<Long, BaseBlock>()
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock {
                val key = if (flat) {
                    (position.x().toLong() shl 32) xor (position.z().toLong() and 0xffffffffL)
                } else {
                    position.x().toLong() * 31 + position.y().toLong() * 17 + position.z()
                }
                return cache.getOrPut(key) { inner.applyBlock(position) }
            }
        }
    }

    private fun linear(worldEdit: WorldEdit, context: ParserContext, args: List<String>?, axes: Int): Pattern? {
        if (args.isNullOrEmpty()) {
            return null
        }
        val patterns = childPatterns(worldEdit, context, args[0])
        if (patterns.isEmpty()) {
            return null
        }
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock {
                val distance = when (axes) {
                    2 -> abs(position.x()) + abs(position.z())
                    3 -> abs(position.x()) + abs(position.y()) + abs(position.z())
                    else -> abs(position.x())
                }
                return patterns[Math.floorMod(distance, patterns.size)].applyBlock(position)
            }
        }
    }

    private fun masked(worldEdit: WorldEdit, context: ParserContext, args: List<String>?): Pattern? {
        if (args == null || args.size < 3) {
            return null
        }
        val mask = worldEdit.maskFactory.parseFromInput(args[0], context)
        val primary = worldEdit.patternFactory.parseFromInput(args[1], context)
        val secondary = worldEdit.patternFactory.parseFromInput(args[2], context)
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock {
                return if (mask.test(position)) primary.applyBlock(position) else secondary.applyBlock(position)
            }
        }
    }

    private fun locked(worldEdit: WorldEdit, context: ParserContext, input: String, axis: Int): Pattern? {
        val prefix = listOf("#!x", "#nox", "#nx", "#!y", "#noy", "#ny", "#!z", "#noz", "#nz").firstOrNull { input.startsWith(it) }
            ?: return null
        val args = brackets(input, prefix) ?: return null
        if (args.isEmpty()) {
            return null
        }
        val inner = worldEdit.patternFactory.parseFromInput(args[0], context)
        var lockedX = Int.MIN_VALUE
        var lockedY = Int.MIN_VALUE
        var lockedZ = Int.MIN_VALUE
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock {
                if (lockedX == Int.MIN_VALUE) {
                    lockedX = position.x()
                    lockedY = position.y()
                    lockedZ = position.z()
                }
                val sample = when (axis) {
                    0 -> position.withX(lockedX)
                    1 -> position.withY(lockedY)
                    else -> position.withZ(lockedZ)
                }
                return inner.applyBlock(sample)
            }
        }
    }

    private fun offset(worldEdit: WorldEdit, context: ParserContext, args: List<String>?, solid: Boolean, surface: Boolean, randomize: Boolean): Pattern? {
        if (args.isNullOrEmpty()) {
            return null
        }
        val inner = worldEdit.patternFactory.parseFromInput(args[0], context)
        val dx = args.getOrNull(1)?.toIntOrNull() ?: 0
        val dy = args.getOrNull(2)?.toIntOrNull() ?: 0
        val dz = args.getOrNull(3)?.toIntOrNull() ?: 0
        val extent = context.extent
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock {
                val shifted = if (surface) {
                    val reach = max(1, abs(dx))
                    val x = position.x() + Random.nextInt(-reach, reach + 1)
                    val z = position.z() + Random.nextInt(-reach, reach + 1)
                    val top = columnTop(extent, x, z)
                    BlockVector3.at(x, top, z)
                } else if (randomize) {
                    val ox = if (dx == 0) 0 else Random.nextInt(-abs(dx), abs(dx) + 1)
                    val oy = if (dy == 0) 0 else Random.nextInt(-abs(dy), abs(dy) + 1)
                    val oz = if (dz == 0) 0 else Random.nextInt(-abs(dz), abs(dz) + 1)
                    position.add(ox, oy, oz)
                } else {
                    position.add(dx, dy, dz)
                }
                if (solid && extent != null && !extent.getBlock(shifted).blockType.material.isSolid) {
                    return inner.applyBlock(position)
                }
                return inner.applyBlock(shifted)
            }
        }
    }

    private fun relative(worldEdit: WorldEdit, context: ParserContext, input: String): Pattern? {
        val prefix = listOf("#relative", "#rel", "#~", "#r").first { input.startsWith(it) }
        val args = brackets(input, prefix) ?: return null
        if (args.isEmpty()) {
            return null
        }
        val inner = worldEdit.patternFactory.parseFromInput(args[0], context)
        var origin: BlockVector3? = null
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock {
                val start = origin ?: position.also { origin = it }
                return inner.applyBlock(position.subtract(start))
            }
        }
    }

    private fun fullCopy(context: ParserContext, args: List<String>?): Pattern? {
        val clipboard = context.session?.clipboard?.clipboard
            ?: throw InputParseException(TranslatableComponent.of("fastasyncworldedit.pattern.clipboard.empty"))
        val origin = clipboard.origin
        val region = clipboard.region
        val positions = region.toList()
        if (positions.isEmpty()) {
            throw InputParseException(TranslatableComponent.of("fastasyncworldedit.pattern.clipboard.empty"))
        }
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock {
                val picked = positions[Random.nextInt(positions.size)]
                return clipboard.getFullBlock(picked)
            }
        }
    }

    private fun typeSwap(extent: Extent?, args: List<String>?): Pattern? {
        if (args == null || args.size < 2) {
            return null
        }
        val from = args[0]
        val to = args[1]
        return object : AbstractPattern() {
            override fun applyBlock(position: BlockVector3): BaseBlock {
                val state = extent?.getBlock(position) ?: return BlockTypes.AIR!!.defaultState.toBaseBlock()
                val replaced = state.asString.replace(from, to)
                val parsed = try {
                    val parserContext = ParserContext()
                    parserContext.extent = extent
                    parserContext.isRestricted = false
                    WorldEdit.getInstance().blockFactory.parseFromInput(replaced, parserContext).toBaseBlock()
                } catch (_: Exception) {
                    state.toBaseBlock()
                }
                return parsed
            }
        }
    }

    private fun columnTop(extent: Extent?, x: Int, z: Int): Int {
        if (extent == null) {
            return 0
        }
        var y = extent.maximumPoint.y()
        val minY = extent.minimumPoint.y()
        while (y > minY && !extent.getBlock(BlockVector3.at(x, y, z)).blockType.material.isMovementBlocker) {
            y--
        }
        return y
    }

    private val palette: List<BaseBlock> by lazy {
        listOf(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
        ).map { name ->
            BlockTypes.get("minecraft:${name}_wool")?.defaultState?.toBaseBlock()
                ?: BlockTypes.STONE!!.defaultState.toBaseBlock()
        }
    }

    private val colors = arrayOf(
        intArrayOf(233, 236, 236), intArrayOf(240, 118, 19), intArrayOf(189, 68, 179), intArrayOf(58, 175, 217),
        intArrayOf(248, 198, 39), intArrayOf(112, 185, 25), intArrayOf(237, 141, 172), intArrayOf(62, 68, 71),
        intArrayOf(142, 142, 134), intArrayOf(21, 137, 145), intArrayOf(121, 42, 172), intArrayOf(53, 57, 157),
        intArrayOf(114, 71, 40), intArrayOf(84, 109, 27), intArrayOf(161, 39, 34), intArrayOf(20, 21, 25),
    )

    /**
     * 按红绿蓝找最接近的羊毛方块。
     *
     * @param red 红色，0 到 255。
     * @param green 绿色，0 到 255。
     * @param blue 蓝色，0 到 255。
     * @return 调色板里距离最近的方块。
     */
    @JvmStatic
    fun coloredBlock(red: Int, green: Int, blue: Int): BaseBlock = nearest(red, green, blue)

    private fun nearest(red: Int, green: Int, blue: Int): BaseBlock {
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (index in colors.indices) {
            val color = colors[index]
            val distance = sq(color[0] - red) + sq(color[1] - green) + sq(color[2] - blue)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return palette[best]
    }

    private fun colorOf(state: BlockState?): IntArray {
        if (state == null) {
            return intArrayOf(128, 128, 128)
        }
        val id = state.blockType.id
        val names = listOf(
            "light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime", "pink", "gray",
            "cyan", "purple", "blue", "brown", "green", "red", "black",
        )
        val index = names.indexOfFirst { id.contains(it) }.let { found ->
            if (found < 0) {
                null
            } else {
                listOf(
                    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
                ).indexOf(names[found])
            }
        }
        return if (index == null) intArrayOf(128, 128, 128) else colors[index]
    }

    private fun sq(value: Int) = value * value
}
