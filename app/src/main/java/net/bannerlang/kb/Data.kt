package net.bannerlang.kb

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Grapheme(val id: String, var banner: Banner, var group: String? = null)

class Group(val id: String, var icon: Banner? = null)

class KeyPage(val group: Group?, val keys: List<Grapheme>, val icon: Banner)

class Word(val id: String, var banners: List<Banner>, var spellings: List<List<String>>, var uses: Int = 0) {
    val shortest get() = spellings.minOfOrNull { it.size } ?: 0
}

enum class InputMode(val key: String, val label: String) {
    STANDARD("standard", "Standard"),
    LAYERING("layering", "Layering"),
}

class Language(
    val id: String,
    var icon: Banner,
    val graphemes: MutableList<Grapheme> = mutableListOf(),
    val words: MutableList<Word> = mutableListOf(),
    var rtl: Boolean = false,
    val groups: MutableList<Group> = mutableListOf(),
    var ungroupedIcon: Banner? = null,
    var input: InputMode = InputMode.STANDARD,
    var keepWords: Boolean = false
) {
    fun grapheme(id: String) = graphemes.firstOrNull { it.id == id }

    fun inGroup(g: Group?): List<Grapheme> {
        val ids = groups.map { it.id }.toSet()
        return graphemes.filter { if (g == null) it.group !in ids else it.group == g.id }
    }

    fun compose(typed: List<Grapheme>): List<Banner> {
        if (input == InputMode.STANDARD) return typed.map { it.banner }
        val out = mutableListOf<Banner>()
        for (g in typed) {
            val last = out.lastOrNull()
            if (last != null && last.layers.size + g.banner.layers.size <= Patterns.LOOM_LAYERS)
                out[out.size - 1] = last.copy(layers = last.layers + g.banner.layers)
            else out.add(g.banner)
        }
        return out
    }

    fun groupIcon(g: Group?) = if (g == null) ungroupedIcon else g.icon

    fun pages(): List<KeyPage> = (listOf<Group?>(null) + groups).mapNotNull { g ->
        val keys = inGroup(g)
        if (keys.isEmpty()) null else KeyPage(g, keys, groupIcon(g) ?: keys.first().banner)
    }

    fun cloneGroup(g: Group?): Group {
        val copy = Group(newId(), groupIcon(g))
        groups.add(if (g == null) 0 else groups.indexOf(g) + 1, copy)
        inGroup(g).forEach { graphemes.add(Grapheme(newId(), it.banner, copy.id)) }
        return copy
    }

    fun swapGroupColor(g: Group?, from: Int, to: Int) {
        for (k in inGroup(g)) k.banner = k.banner.swapColor(from, to)
    }

    fun reorder(keys: List<Grapheme>, from: Int, to: Int) {
        val slots = keys.map { graphemes.indexOf(it) }
        val moved = keys.toMutableList().apply { add(to, removeAt(from)) }
        slots.forEachIndexed { k, s -> graphemes[s] = moved[k] }
    }

    fun suggestions(seq: List<String>, byUses: Boolean = true): List<Word> {
        if (seq.isEmpty()) return emptyList()
        fun startsWith(s: List<String>) = s.size >= seq.size && s.subList(0, seq.size) == seq
        val prefix = words.filter { w -> w.spellings.any { startsWith(it) } }
            .sortedBy { w -> w.spellings.filter { startsWith(it) }.minOf { it.size } }
        val infix = words.filter { w ->
            w !in prefix && w.spellings.any { s -> (1..s.size - seq.size).any { s.subList(it, it + seq.size) == seq } }
        }
        return if (byUses) (prefix + infix).sortedByDescending { it.uses } else prefix + infix
    }
}

fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

enum class Output(val key: String, val label: String, val hint: String) {
    IMAGE("image", "Image", "Works in any app that takes images."),
    LINK("link", "banner-writer link", "Link to the image on banner-writer.web.app, works anywhere."),
}

object Repo {
    private const val TAG = "Repo"
    private var file: File? = null

    val languages = mutableListOf<Language>()
    var selectedId: String? = null
        private set

    var output: Output
        get() = outputValue
        set(v) { outputValue = v; save() }
    private var outputValue = Output.IMAGE // set directly while loading, so load doesn't save

    var exportScale: Int
        get() = exportScaleValue
        set(v) { exportScaleValue = v; save() }
    private var exportScaleValue = 1

    var chatWidth: Int
        get() = chatWidthValue
        set(v) { chatWidthValue = v; save() }
    private var chatWidthValue = 0

    var frequentFirst: Boolean
        get() = frequentFirstValue
        set(v) { frequentFirstValue = v; save() }
    private var frequentFirstValue = true

    var version = 0
        private set

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "data.json")
        Backup.init(ctx)
        load()
    }

    fun language(id: String?) = languages.firstOrNull { it.id == id }

    fun selected(): Language? = language(selectedId) ?: languages.firstOrNull()

    fun select(id: String) {
        selectedId = id
        save()
    }

    fun save() {
        version++
        val f = file ?: return
        val root = JSONObject()
        root.put("version", 1)
        root.put("selected", selectedId)
        settingsToJson().let { o -> o.keys().forEach { root.put(it, o.get(it)) } }
        root.put("languages", JSONArray().apply { languages.forEach { put(langToJson(it)) } })
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(f)) Log.e(TAG, "could not save data")
        Backup.onSaved()
    }

    fun settingsToJson(): JSONObject = JSONObject()
        .put("output", output.key)
        .put("scale", exportScale)
        .put("chatWidth", chatWidth)
        .put("frequentFirst", frequentFirst)

    fun readSettings(o: JSONObject) {
        if (o.has("output")) Output.entries.find { it.key == o.optString("output") }?.let { outputValue = it }
        if (o.has("scale")) o.optInt("scale", 1).takeIf { it in BannerRenderer.EXPORT_SCALES }?.let { exportScaleValue = it }
        if (o.has("chatWidth")) o.optInt("chatWidth", 0).takeIf { it in BannerRenderer.CHAT_WIDTHS }?.let { chatWidthValue = it }
        if (o.has("frequentFirst")) frequentFirstValue = o.optBoolean("frequentFirst", true)
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            selectedId = root.optString("selected").ifEmpty { null }
            readSettings(root)
            val arr = root.optJSONArray("languages") ?: JSONArray()
            for (i in 0 until arr.length()) languages.add(langFromJson(arr.getJSONObject(i)))
        } catch (e: Exception) {
            Log.e(TAG, "could not load data", e)
            f.copyTo(File(f.parentFile, "data.broken.json"), overwrite = true)
        }
        version++
    }

    private fun langToJson(l: Language) = JSONObject().apply {
        put("id", l.id)
        put("icon", l.icon.encode())
        put("rtl", l.rtl)
        put("keepWords", l.keepWords)
        put("graphemes", JSONArray().apply {
            l.graphemes.forEach { g -> put(JSONObject().put("id", g.id).put("b", g.banner.encode()).putOpt("g", g.group)) }
        })
        putOpt("ungroupedIcon", l.ungroupedIcon?.encode())
        put("input", l.input.key)
        put("groups", JSONArray().apply {
            l.groups.forEach { g -> put(JSONObject().put("id", g.id).putOpt("icon", g.icon?.encode())) }
        })
        put("words", JSONArray().apply {
            l.words.forEach { w ->
                put(JSONObject().put("id", w.id)
                    .put("b", JSONArray(w.banners.map { it.encode() }))
                    .put("s", JSONArray(w.spellings.map { JSONArray(it) }))
                    .apply { if (w.uses > 0) put("n", w.uses) })
            }
        })
    }

    private fun langFromJson(o: JSONObject): Language {
        val l = Language(o.getString("id"), Banner.decode(o.getString("icon")), rtl = o.optBoolean("rtl"),
            keepWords = o.optBoolean("keepWords"))
        val gs = o.optJSONArray("graphemes") ?: JSONArray()
        for (i in 0 until gs.length()) {
            val g = gs.getJSONObject(i)
            l.graphemes.add(Grapheme(g.getString("id"), Banner.decode(g.getString("b")), g.optString("g").ifEmpty { null }))
        }
        l.ungroupedIcon = o.optString("ungroupedIcon").ifEmpty { null }?.let { Banner.decode(it) }
        l.input = InputMode.entries.find { it.key == o.optString("input") } ?: InputMode.STANDARD
        val grs = o.optJSONArray("groups") ?: JSONArray()
        for (i in 0 until grs.length()) {
            val g = grs.getJSONObject(i)
            l.groups.add(Group(g.getString("id"), g.optString("icon").ifEmpty { null }?.let { Banner.decode(it) }))
        }
        val ws = o.optJSONArray("words") ?: JSONArray()
        for (i in 0 until ws.length()) {
            val w = ws.getJSONObject(i)
            val b = w.getJSONArray("b")
            val s = w.getJSONArray("s")
            val spellings = List(s.length()) { i -> s.getJSONArray(i).let { a -> List(a.length()) { a.getString(it) } } }
            l.words.add(Word(w.getString("id"), List(b.length()) { Banner.decode(b.getString(it)) }, spellings, w.optInt("n")))
        }
        return l
    }
}
