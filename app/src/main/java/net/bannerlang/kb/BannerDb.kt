package net.bannerlang.kb

import org.json.JSONArray
import org.json.JSONObject

object BannerDb {
    fun parse(text: String, into: Language): Backup.Contents {
        val root = JSONObject(text)
        val arr = root.getJSONArray("banners")
        val l = Language(newId(), into.icon, rtl = into.rtl, keepWords = into.keepWords).apply { input = into.input }
        val byName = HashMap<String, Grapheme>()
        val words = mutableListOf<Pair<String, Banner>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val str = o.getString("str")
            val b = if (str.isEmpty()) null else
                try { BannerWriter.parseText(str).lines.flatten().singleOrNull() } catch (_: RuntimeException) { null }
            if (b == null) continue
            val id = o.optString("id")
            if (o.optBoolean("grapheme")) {
                val g = l.graphemes.firstOrNull { it.banner == b } ?: Grapheme(newId(), b).also { l.graphemes.add(it) }
                if (id.isNotEmpty()) byName.putIfAbsent(id, g)
            } else words.add(id to b)
        }
        val walls = writings(root.optJSONArray("writings"))
        if (l.graphemes.isEmpty() && words.isEmpty() && walls.isEmpty()) throw IllegalArgumentException("No banners")
        for ((id, b) in words.distinctBy { it.second }) {
            val s = split(id, byName)
            l.words.add(Word(newId(), listOf(b), if (s == null) emptyList() else listOf(s.map { it.id })))
        }
        return Backup.Contents(listOf(l), walls)
    }

    private fun writings(arr: JSONArray?): List<Doc> {
        if (arr == null) return emptyList()
        val now = System.currentTimeMillis()
        return (0 until arr.length()).mapNotNull { i ->
            val w = try { BannerWriter.parseText(arr.optString(i)) } catch (_: RuntimeException) { null }
            if (w == null || w.lines.none { l -> l.any { it != null } }) return@mapNotNull null
            val cols = maxOf(Doc.DEFAULT_COLUMNS, w.lines.maxOf { it.size }).coerceAtMost(32)
            Doc(newId(), cols, w.rtl, w.lines, now)
        }
    }

    private fun split(s: String, names: Map<String, Grapheme>): List<Grapheme>? {
        if (s.isEmpty()) return null
        val best = arrayOfNulls<List<Grapheme>>(s.length + 1)
        best[0] = emptyList()
        for (i in 0 until s.length) {
            val head = best[i] ?: continue
            for ((name, g) in names) {
                if (name.isEmpty() || !s.startsWith(name, i)) continue
                val j = i + name.length
                if (best[j] == null || best[j]!!.size > head.size + 1) best[j] = head + g
            }
        }
        return best[s.length]
    }

    fun looksLike(text: String) = try { JSONObject(text).has("banners") } catch (_: Exception) { false }
}
