package net.bannerlang.kb

object DyeColors {
    val NAMES = arrayOf(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    )
    val RGB = intArrayOf(
        0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
        0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21
    )
    const val WHITE = 0
    const val BLACK = 15

    fun argb(color: Int): Int = 0xFF000000.toInt() or RGB[color]
    fun index(name: String): Int = NAMES.indexOf(name).coerceAtLeast(0)

    fun backdrop(base: Int, color: Int): Int {
        val a = RGB[base]; val b = RGB[color]
        fun d(shift: Int) = ((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)
        val dist = Math.sqrt((d(16) * d(16) + d(8) * d(8) + d(0) * d(0)).toDouble())
        if (dist >= 90) return base
        val luma = 0.299 * ((b shr 16) and 0xFF) + 0.587 * ((b shr 8) and 0xFF) + 0.114 * (b and 0xFF)
        return if (luma > 128) BLACK else WHITE
    }
}

data class Layer(val pattern: String, val color: Int)

data class Banner(val base: Int, val layers: List<Layer> = emptyList(), val clearBase: Boolean = false) {
    fun encode(): String = buildString {
        if (clearBase) append('-')
        append(DyeColors.NAMES[base])
        for (l in layers) append('|').append(DyeColors.NAMES[l.color]).append(':').append(l.pattern)
    }

    fun swapColor(from: Int, to: Int): Banner {
        fun c(x: Int) = if (x == from) to else x
        return Banner(c(base), layers.map { it.copy(color = c(it.color)) }, clearBase)
    }

    fun colors() = setOf(base) + layers.map { it.color }

    companion object {
        val BLANK = Banner(DyeColors.WHITE)

        fun decode(s: String): Banner {
            val parts = s.split('|')
            val layers = parts.drop(1).mapNotNull { p ->
                val i = p.indexOf(':')
                if (i < 0) null else Layer(p.substring(i + 1), DyeColors.index(p.substring(0, i)))
            }
            return Banner(DyeColors.index(parts[0].removePrefix("-")), layers, parts[0].startsWith("-"))
        }
    }
}
