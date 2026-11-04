package net.bannerlang.kb

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.round
import kotlin.math.sqrt

object Patterns {
    const val W = 20
    const val H = 40
    const val MAX_LAYERS = 16
    const val LOOM_LAYERS = 6

    class Pattern(val id: String, val mask: FloatArray?)

    fun list(): List<Pattern> {
        if (!Textures.installed) return ALL.filter { it.id != "base" }
        val ids = Textures.patternIds
        val known = ALL.filter { it.id in ids && it.id != "base" }
        val extra = ids.filter { byId[it] == null }.map { Pattern(it, null) }
        return known + extra
    }

    val ALL: List<Pattern> = listOf(
        Pattern("base", mask { _, _ -> true }),
        Pattern("stripe_bottom", mask { _, y -> y >= 27 }),
        Pattern("stripe_top", mask { _, y -> y < 13 }),
        Pattern("stripe_left", mask { x, _ -> x < 6 }),
        Pattern("stripe_right", mask { x, _ -> x >= 14 }),
        Pattern("stripe_center", mask { x, _ -> x in 7..12 }),
        Pattern("stripe_middle", mask { _, y -> y in 17..22 }),
        Pattern("stripe_downright", mask { x, y -> bend(x, y) }),
        Pattern("stripe_downleft", mask { x, y -> bend(W - 1 - x, y) }),
        Pattern("small_stripes", mask { x, _ -> x % 4 == 1 || x % 4 == 2 }),
        Pattern("cross", mask { x, y -> bend(x, y) || bend(W - 1 - x, y) }),
        Pattern("straight_cross", mask { x, y -> x in 7..12 || y in 17..22 }),
        Pattern("diagonal_left", mask { x, y -> fx(x) + fy(y) < 1f }),
        Pattern("diagonal_right", mask { x, y -> fx(x) > fy(y) }),
        Pattern("diagonal_up_left", mask { x, y -> fx(x) < fy(y) }),
        Pattern("diagonal_up_right", mask { x, y -> fx(x) + fy(y) > 1f }),
        Pattern("half_vertical", mask { x, _ -> x < 10 }),
        Pattern("half_vertical_right", mask { x, _ -> x >= 10 }),
        Pattern("half_horizontal", mask { _, y -> y < 20 }),
        Pattern("half_horizontal_bottom", mask { _, y -> y >= 20 }),
        Pattern("square_bottom_left", mask { x, y -> x < 8 && y >= 27 }),
        Pattern("square_bottom_right", mask { x, y -> x >= 12 && y >= 27 }),
        Pattern("square_top_left", mask { x, y -> x < 8 && y < 13 }),
        Pattern("square_top_right", mask { x, y -> x >= 12 && y < 13 }),
        Pattern("triangle_bottom", mask { x, y -> chevron(x, H - 1 - y) }),
        Pattern("triangle_top", mask { x, y -> chevron(x, y) }),
        Pattern("triangles_bottom", mask { x, y -> teeth(x, H - 1 - y) }),
        Pattern("triangles_top", mask { x, y -> teeth(x, y) }),
        Pattern("circle", mask { x, y -> sq(x + .5f - 10f) + sq(y + .5f - 20f) <= 26f }),
        Pattern("rhombus", mask { x, y -> abs(x + .5f - 10f) / 7.5f + abs(y + .5f - 20f) / 11f <= 1f }),
        Pattern("border", mask { x, y -> x < 1 || x >= W - 1 || y < 1 || y >= H - 1 }),
        Pattern("curly_border", mask { x, y ->
            x < 1 || x >= W - 1 || y < 1 || y >= H - 1 ||
                ((x == 1 || x == W - 2 || y == 1 || y == H - 2) && (x + y) % 2 == 0)
        }),
        Pattern("gradient", maskF { _, y -> 1f - y / (H - 1f) }),
        Pattern("gradient_up", maskF { _, y -> y / (H - 1f) }),
        Pattern("bricks", mask { x, y ->
            y % 4 == 3 || (x + if ((y / 4) % 2 == 0) 0 else 4) % 8 == 7
        }),
        Pattern("globe", mask { x, y -> globe(x, y) }),
        Pattern("creeper", art(2,
            "........",
            ".##..##.",
            ".##..##.",
            "...##...",
            "..####..",
            "..####..",
            "..#..#..",
            "........")),
        Pattern("skull", art(1,
            "...######...",
            "..########..",
            ".##########.",
            ".##..##..##.",
            ".##..##..##.",
            ".##########.",
            "..###..###..",
            "...######...",
            "...#.##.#...",
            "............",
            "#..........#",
            ".##......##.",
            "...##..##...",
            ".....##.....",
            "...##..##...",
            ".##......##.",
            "#..........#")),
        Pattern("flower", art(1,
            ".....##.....",
            "....####....",
            ".##..##..##.",
            ".###....###.",
            "..#.####.#..",
            "...######...",
            "...######...",
            "..#.####.#..",
            ".###....###.",
            ".##..##..##.",
            "....####....",
            ".....##.....")),
        Pattern("mojang", art(1,
            "...######...",
            ".##......##.",
            ".#..####..#.",
            "#..#....#..#",
            "#.#..##..#.#",
            "#.#.#..#.#.#",
            "#.#.#..#...#",
            "#.#..##....#",
            "#..#.......#",
            ".#..######.#",
            ".##......##.",
            "...######...")),
        Pattern("piglin", art(1,
            "..########..",
            ".##########.",
            ".##..##..##.",
            ".##..##..##.",
            ".##########.",
            "..########..")),
        Pattern("flow", mask { x, y -> spiral(x, y) }),
        Pattern("guster", art(1,
            "....#####.....",
            "...#.....#....",
            "..........#...",
            "#########.#...",
            "..........#...",
            "...######.....",
            "..............",
            "#############.",
            "..............",
            "...######.....",
            "..........#...",
            "#########.#...",
            "..........#...",
            "...#.....#....",
            "....#####....."))
    )

    private val byId = ALL.associateBy { it.id }

    fun get(id: String): Pattern? = byId[id]

    private fun mask(f: (Int, Int) -> Boolean) = FloatArray(W * H) { i -> if (f(i % W, i / W)) 1f else 0f }

    private fun maskF(f: (Int, Int) -> Float) = FloatArray(W * H) { i -> f(i % W, i / W) }

    private fun art(scale: Int, vararg rows: String): FloatArray {
        val w = rows.maxOf { it.length } * scale
        val h = rows.size * scale
        val ox = (W - w) / 2
        val oy = (H - h) / 2
        return mask { x, y ->
            val ax = x - ox
            val ay = y - oy
            ax >= 0 && ay >= 0 && ax < w && ay < h && rows[ay / scale].getOrNull(ax / scale) == '#'
        }
    }

    private fun fx(x: Int) = (x + .5f) / W
    private fun fy(y: Int) = (y + .5f) / H
    private fun sq(v: Float) = v * v

    private fun bend(x: Int, y: Int) = abs((x + .5f) - (y + .5f) / 2f) < 2f

    private fun chevron(x: Int, k: Int) = k < 10 && x >= k && x <= W - 1 - k

    private fun teeth(x: Int, k: Int): Boolean {
        val d = abs((x % 4) - 1.5f)
        return k < 1 + (4 - 2 * d)
    }

    private fun globe(x: Int, y: Int): Boolean {
        val dx = x + .5f - 10f
        val dy = y + .5f - 20f
        val r = sqrt(dx * dx + dy * dy)
        if (r > 6.6f) return false
        if (r >= 5.6f) return true
        if (abs(dy) < .6f || abs(dx) < .6f) return true
        val e = sqrt(sq(dx / 3f) + sq(dy / 6f))
        return abs(e - 1f) < .17f
    }

    private fun spiral(x: Int, y: Int): Boolean {
        val dx = x + .5f - 10f
        val dy = y + .5f - 20f
        val r = sqrt(dx * dx + dy * dy)
        if (r > 8.5f) return false
        val twoPi = (2 * Math.PI).toFloat()
        val a = 3f / twoPi
        var th = atan2(dy, dx)
        if (th < 0) th += twoPi
        val t = r / a - th
        val k = round(t / twoPi)
        return k >= 0 && abs(t - twoPi * k) * a < .8f
    }
}
