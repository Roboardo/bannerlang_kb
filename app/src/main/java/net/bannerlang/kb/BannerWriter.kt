package net.bannerlang.kb

import java.net.URLDecoder

// attempting to port banner writer formats from JS to kotlin
object BannerWriter {
    class Writing(val rtl: Boolean, val lines: List<List<Banner?>>)

    private val PATTERNS = listOf(
        "base", "border", "bricks", "circle", "creeper", "cross",
        "curly_border", "diagonal_left", "diagonal_right", "diagonal_up_left", "diagonal_up_right",
        "flower", "globe", "gradient", "gradient_up", "half_horizontal",
        "half_horizontal_bottom", "half_vertical", "half_vertical_right", "mojang", "piglin",
        "rhombus", "skull", "small_stripes", "square_bottom_left", "square_bottom_right",
        "square_top_left", "square_top_right", "straight_cross", "stripe_bottom", "stripe_center",
        "stripe_downleft", "stripe_downright", "stripe_left", "stripe_middle", "stripe_right",
        "stripe_top", "triangle_bottom", "triangle_top", "triangles_bottom", "triangles_top",
        "flow", "guster"
    )
    private val CODES = listOf(
        "b", "bo", "bri", "mc", "cre", "cr", "cbo", "ld", "rud", "lud", "rd",
        "flo", "glb", "gra", "gru", "hh", "hhb", "vh", "vhr", "moj", "pig",
        "mr", "sku", "ss", "bl", "br", "tl", "tr", "sc", "bs", "cs",
        "dls", "drs", "ls", "ms", "rs", "ts", "bt", "tt", "bts", "tts", "flw", "gus"
    )
    private val COLOR_TO_DYE = intArrayOf(0, 8, 7, 15, 4, 1, 14, 12, 5, 13, 3, 9, 11, 6, 2, 10)
    private val DYE_TO_COLOR = IntArray(16).also { a -> COLOR_TO_DYE.forEachIndexed { c, d -> a[d] = c } }

    private const val LAYER = 0xE000
    private const val LTR = 0xE00E
    private const val RTL = 0xE00F
    private const val NEWLINE = 0xE00A
    private const val END = 0xE00D
    private const val JOIN = 0xCFFF7
    private const val URL_HEX = "0123456789ABCDEF"
    private const val URL_PAT = ".GHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private fun layerChar(dye: Int, pattern: Int): String {
        val cp = LAYER + DYE_TO_COLOR[dye] * 256 + (pattern / 10) * 16 + pattern % 10
        return String(Character.toChars(cp))
    }

    internal fun bannerText(b: Banner) = buildString {
        append(layerChar(b.base, 0))
        for (l in b.layers) {
            val p = PATTERNS.indexOf(l.pattern)
            if (p <= 0) continue
            appendCodePoint(JOIN)
            append(layerChar(l.color, p))
        }
    }

    fun toText(w: Writing): String = buildString {
        appendCodePoint(if (w.rtl) RTL else LTR)
        w.lines.forEachIndexed { i, line ->
            if (i != 0) appendCodePoint(NEWLINE).append('\n')
            for (b in if (w.rtl) line.reversed() else line) append(if (b != null) bannerText(b) else " ")
        }
        appendCodePoint(END)
    }

    fun parseText(s: String): Writing {
        var t = 0
        var rtl = false
        fun cp(i: Int) = if (i < s.length) s.codePointAt(i) else -1
        when (cp(t)) {
            LTR -> t++
            RTL -> { rtl = true; t++ }
        }
        val lines = mutableListOf<List<Banner?>>()
        var cols = HashMap<Int, Pair<Int, MutableList<Layer>>>()
        var maxCol = -1
        var col = 0
        fun endLine() {
            lines.add((0..maxCol).map { c -> cols[c]?.let { Banner(it.first, it.second) } })
            cols = HashMap(); maxCol = -1; col = 0
        }
        while (true) {
            val n = cp(t)
            if (n < 0) break
            if (n == END) { t++; break }
            var nl = false
            if (n == NEWLINE) { t++; nl = true }
            if (cp(t) == 13) { t++; nl = true }
            if (cp(t) == 10 || (nl && (cp(t) == 32 || cp(t) == 95))) { t++; nl = true }
            if (nl) { endLine(); continue }
            if (n == 32 || n == 95) { t++; col++; continue }
            if (n in 0xF000..0xF080) { col += n - 0xF040; t++; continue }
            if (n in 847872..856064) { col += (n - 851968) / 9; t += 2; continue }
            if (n < LAYER || n >= 0xF000) throw IllegalArgumentException("Not BannerFont text")
            val color = (n and 0xF00) shr 8
            val tens = (n and 0xF0) shr 4
            val ones = n and 0xF
            if (tens >= 10 || ones >= 10 || tens * 10 + ones >= PATTERNS.size) throw IllegalArgumentException("Bad pattern in text")
            val pattern = tens * 10 + ones
            val dye = COLOR_TO_DYE[color]
            if (pattern == 0) {
                cols[col] = dye to mutableListOf()
                maxCol = maxOf(maxCol, col)
            } else {
                val b = cols[col] ?: throw IllegalArgumentException("Layer without a banner")
                b.second.add(Layer(PATTERNS[pattern], dye))
            }
            t++
            col++
        }
        endLine()
        return Writing(rtl, if (rtl) lines.map { it.reversed() } else lines)
    }

    fun hasCode(b: Banner) = !b.clearBase && b.layers.all { PATTERNS.indexOf(it.pattern) > 0 }

    fun toCode(b: Banner) = buildString {
        append("b").append(b.base)
        for (l in b.layers) {
            val p = PATTERNS.indexOf(l.pattern)
            if (p > 0) append(CODES[p]).append(l.color)
        }
    }

    fun parseCode(s: String): Banner {
        var t = 0
        fun layer(): Pair<Int, Int> {
            val a = t
            while (t < s.length && s[t] in 'a'..'z') t++
            val code = s.substring(a, t)
            val b = t
            while (t < s.length && s[t] in '0'..'9') t++
            val p = CODES.indexOf(code)
            val dye = s.substring(b, t).toIntOrNull()
            if (p < 0 || dye == null || dye !in 0..15) throw IllegalArgumentException("Bad banner code")
            return p to dye
        }
        val (p0, base) = layer()
        if (p0 != 0) throw IllegalArgumentException("Banner code must start with b")
        val layers = mutableListOf<Layer>()
        while (t < s.length && s[t] in 'a'..'z') {
            val (p, dye) = layer()
            layers.add(Layer(PATTERNS[p], dye))
        }
        if (t != s.length) throw IllegalArgumentException("Bad banner code")
        return Banner(base, layers)
    }

    fun toUrlSafe(w: Writing): String = buildString {
        append(if (w.rtl) 'L' else 'R')
        var color = 0
        fun color(dye: Int) {
            val c = DYE_TO_COLOR[dye]
            if (c != color) { append(URL_HEX[c]); color = c }
        }
        w.lines.forEachIndexed { i, line ->
            if (i != 0) append('~')
            for (b in if (w.rtl) line.reversed() else line) {
                if (b == null) { append('_'); continue }
                color(b.base)
                append('.')
                for (l in b.layers) {
                    val p = PATTERNS.indexOf(l.pattern)
                    if (p <= 0) continue
                    color(l.color)
                    append(URL_PAT[p])
                }
            }
        }
    }

    // replace with new banner writer url format whenever it drops
    fun imageLink(w: Writing) = "https://banner-writer.web.app/image/${toUrlSafe(w)}.png"

    fun parseUrlSafe(s: String): Writing {
        if (s.isEmpty()) throw IllegalArgumentException("Empty")
        var color = 0
        val rtl = s[0] == 'L'
        val lines = mutableListOf(mutableListOf<Pair<Int, MutableList<Layer>>?>())
        for (ch in s.substring(1)) {
            val line = lines.last()
            when {
                ch == '~' -> lines.add(mutableListOf())
                ch == '_' -> line.add(null)
                ch == '.' -> line.add(COLOR_TO_DYE[color] to mutableListOf())
                ch in URL_HEX -> color = URL_HEX.indexOf(ch)
                ch in URL_PAT -> {
                    val b = line.lastOrNull() ?: throw IllegalArgumentException("Layer without a banner")
                    b.second.add(Layer(PATTERNS.getOrNull(URL_PAT.indexOf(ch)) ?: throw IllegalArgumentException("Bad pattern"), COLOR_TO_DYE[color]))
                }
                else -> throw IllegalArgumentException("Bad link")
            }
        }
        val out = lines.map { l -> l.map { it?.let { (base, layers) -> Banner(base, layers) } } }
        return Writing(rtl, if (rtl) out.map { it.reversed() } else out)
    }

    private fun trimLink(s: String): String {
        var e = s.trim()
        if ('%' in e) e = try { URLDecoder.decode(e, "UTF-8") } catch (_: Exception) { e }
        if (e.endsWith(".png")) e = e.dropLast(4)
        return e.substring(maxOf(e.lastIndexOf('/'), e.lastIndexOf('=')) + 1)
    }

    fun parseAny(input: String): Writing {
        val s = input.trim()
        try { return Writing(false, listOf(listOf(parseCode(s)))) } catch (_: IllegalArgumentException) {}
        try { return parseUrlSafe(trimLink(s)) } catch (_: IllegalArgumentException) {}
        return parseText(s)
    }

    fun parseStorage(input: String): List<Writing> {
        var s = input.trim()
        if (s.length >= 2 && s[0] in "'\"`" && s.last() == s[0]) s = s.substring(1, s.length - 1)
        s = unescape(s)
        val out = mutableListOf<Writing>()
        var i = 0
        while (i < s.length) {
            if (s[i].code != LTR && s[i].code != RTL) { i++; continue }
            val end = s.indexOf(END.toChar(), i + 1).let { if (it < 0) s.length else it + 1 }
            try { out.add(parseText(unescape(s.substring(i, end)))) } catch (_: RuntimeException) {}
            i = end
        }
        return out
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        fun hex(from: Int, len: Int): Int? =
            if (from + len <= s.length && (from until from + len).all { s[it] in "0123456789abcdefABCDEF" })
                s.substring(from, from + len).toInt(16) else null
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { b.append(c); i++; continue }
            val n = s[i + 1]
            val close = if (n == 'u' && s.getOrNull(i + 2) == '{') s.indexOf('}', i + 3) else -1
            val braced = if (close > i + 3) hex(i + 3, close - i - 3)?.takeIf { it <= 0x10FFFF } else null
            when {
                braced != null -> { b.appendCodePoint(braced); i = close + 1 }
                n == 'u' && hex(i + 2, 4) != null -> { b.append(hex(i + 2, 4)!!.toChar()); i += 6 }
                n == 'x' && hex(i + 2, 2) != null -> { b.append(hex(i + 2, 2)!!.toChar()); i += 4 }
                else -> { b.append(when (n) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> n }); i += 2 }
            }
        }
        return b.toString()
    }

    fun findAny(input: String): Writing? {
        fun ok(w: Writing?) = w?.takeIf { it.lines.any { l -> l.any { b -> b != null } } }
        fun tryOk(f: () -> Writing) = try { ok(f()) } catch (_: RuntimeException) { null }
        fun join(ws: List<Writing>) = if (ws.isEmpty()) null else
            Writing(ws[0].rtl, listOf(ws.flatMapIndexed { i, w -> (if (i > 0) listOf(null) else emptyList()) + w.lines.flatten() }))

        tryOk { parseAny(input) }?.let { return it }

        val cps = input.codePoints().toArray()
        fun font(c: Int) = c in LAYER..0xF080 || c == JOIN || c in 847872..856064
        val first = cps.indexOfFirst { font(it) }
        if (first >= 0) {
            val end = cps.indexOfFirst { it == END }.takeIf { it > first } ?: cps.indexOfLast { font(it) }
            tryOk { parseText(String(cps, first, end + 1 - first)) }?.let { return it }
        }

        val words = input.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
        ok(join(words.filter { "banner-writer" in it }.mapNotNull { tryOk { parseUrlSafe(trimLink(it)) } }))?.let { return it }
        return ok(join(words.mapNotNull { w ->
            try { Writing(false, listOf(listOf(parseCode(w.trim('"', '\'', '`', '(', ')', '.'))))) } catch (_: RuntimeException) { null }
        }))
    }
}

fun bannerCode(b: Banner) = if (BannerWriter.hasCode(b)) BannerWriter.toCode(b) else b.encode()

fun parseBannerCode(s: String) = try { BannerWriter.parseCode(s) } catch (_: IllegalArgumentException) { Banner.decode(s) }
