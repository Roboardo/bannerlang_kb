package net.bannerlang.kb

sealed class Token {
    class B(val banner: Banner) : Token()
    object Space : Token()
    object Newline : Token()
}

class Message {
    val tokens = mutableListOf<Token>()
    var anchor = 0
        private set
    var cursor = 0
        private set

    val hasSelection get() = anchor != cursor
    val selStart get() = minOf(anchor, cursor)
    val selEnd get() = maxOf(anchor, cursor)
    val isEmpty get() = tokens.isEmpty()

    fun setCursor(i: Int) {
        cursor = i.coerceIn(0, tokens.size)
        anchor = cursor
    }

    fun select(a: Int, b: Int) {
        anchor = a.coerceIn(0, tokens.size)
        cursor = b.coerceIn(0, tokens.size)
    }

    fun insert(list: List<Token>) {
        deleteSelection()
        tokens.addAll(cursor, list)
        setCursor(cursor + list.size)
    }

    fun deleteSelection(): Boolean {
        if (!hasSelection) return false
        val s = selStart
        repeat(selEnd - s) { tokens.removeAt(s) }
        setCursor(s)
        return true
    }

    fun backspace() {
        if (deleteSelection() || cursor == 0) return
        tokens.removeAt(cursor - 1)
        setCursor(cursor - 1)
    }

    fun clear() {
        tokens.clear()
        setCursor(0)
    }

    fun lines(from: Int = 0, to: Int = tokens.size): List<List<Banner?>> {
        val out = mutableListOf<MutableList<Banner?>>(mutableListOf())
        for (t in tokens.subList(from, to)) when (t) {
            is Token.B -> out.last().add(t.banner)
            Token.Space -> out.last().add(null)
            Token.Newline -> out.add(mutableListOf())
        }
        return out
    }

    fun rows(cols: Int, keepWords: Boolean): List<List<Banner?>> {
        val w = wrap(tokens, cols, keepWords)
        val out = List(w.endLine + 1) { mutableListOf<Banner?>() }
        tokens.forEachIndexed { i, t ->
            val row = out[w.line[i]]
            when (t) {
                is Token.B -> { while (row.size < w.col[i]) row.add(null); row.add(t.banner) }
                Token.Space -> { while (row.size < w.col[i]) row.add(null); row.add(null) }
                Token.Newline -> {}
            }
        }
        out.forEach { row -> while (row.isNotEmpty() && row.last() == null) row.removeAt(row.size - 1) }
        return out
    }

    companion object {
        fun tokensOf(lines: List<List<Banner?>>): List<Token> {
            val out = mutableListOf<Token>()
            lines.forEachIndexed { i, line ->
                if (i > 0) out.add(Token.Newline)
                line.forEach { out.add(if (it == null) Token.Space else Token.B(it)) }
            }
            return out
        }
    }
}

fun pages(rows: List<List<Banner?>>, perPage: Int): List<List<List<Banner?>>> =
    if (perPage <= 0) listOf(rows) else rows.chunked(perPage)

class Wrapped(val line: IntArray, val col: IntArray, val endLine: Int, val endCol: Int)

fun wrap(tokens: List<Token>, cols: Int, keepWords: Boolean): Wrapped {
    val n = tokens.size
    val ls = IntArray(n); val cs = IntArray(n)
    val max = cols.coerceAtLeast(1)
    var x = 0; var line = 0; var i = 0
    while (i < n) {
        when (tokens[i]) {
            Token.Newline -> { ls[i] = line; cs[i] = x; line++; x = 0; i++ }
            Token.Space -> {
                if (x >= max) { line++; x = 0 }
                ls[i] = line; cs[i] = x; x++; i++
            }
            is Token.B -> {
                var j = i
                while (j < n && tokens[j] is Token.B) j++
                if (keepWords && x > 0 && x + (j - i) > max && j - i <= max) { line++; x = 0 }
                for (k in i until j) {
                    if (x >= max) { line++; x = 0 }
                    ls[k] = line; cs[k] = x; x++
                }
                i = j
            }
        }
    }
    return Wrapped(ls, cs, line, x)
}
