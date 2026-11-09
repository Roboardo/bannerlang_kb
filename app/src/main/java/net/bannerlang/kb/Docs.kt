package net.bannerlang.kb

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class Doc(val id: String, var columns: Int, var rtl: Boolean, var lines: List<List<Banner?>>,
          var modified: Long = 0, var pageLines: Int = 0, var pageGap: Boolean = false,
          var title: List<Banner> = emptyList()) {
    fun toJson(): String = toJsonObject().toString()

    fun toJsonObject(): JSONObject = JSONObject().apply {
        if (title.isNotEmpty()) put("title", JSONArray(title.map(::bannerCode)))
        put("columns", columns)
        if (rtl) put("rtl", true)
        if (pageLines > 0) put("pageLines", pageLines)
        if (pageGap) put("pageGap", true)
        put("lines", JSONArray(lines.map { l -> JSONArray(l.map { it?.let(::bannerCode) ?: JSONObject.NULL }) }))
    }

    companion object {
        const val DEFAULT_COLUMNS = 9
        const val MAX_PAGE_LINES = 64

        fun fromJson(id: String, text: String, modified: Long): Doc {
            val o = JSONObject(text)
            val ls = o.getJSONArray("lines")
            val lines = List(ls.length()) { i ->
                val l = ls.getJSONArray(i)
                List(l.length()) { j -> if (l.isNull(j)) null else parseBannerCode(l.getString(j)) }
            }
            val t = o.optJSONArray("title")
            val title = if (t == null) emptyList() else List(t.length()) { parseBannerCode(t.getString(it)) }
            return Doc(id, o.optInt("columns", DEFAULT_COLUMNS).coerceIn(1, 64),
                o.optBoolean("rtl"), lines, modified, o.optInt("pageLines").coerceIn(0, MAX_PAGE_LINES), o.optBoolean("pageGap"),
                title)
        }
    }
}

object Docs {
    private fun dir(ctx: Context) = File(ctx.filesDir, "files").apply { mkdirs() }


    fun list(ctx: Context): List<Doc> = io.submit<List<Doc>> {
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> try { Doc.fromJson(f.name.removeSuffix(".json"), f.readText(), f.lastModified()) } catch (_: Exception) { null } }
            .sortedByDescending { it.modified }
    }.get()

    fun load(ctx: Context, id: String): Doc? = list(ctx).firstOrNull { it.id == id }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun write(ctx: Context, id: String, json: String): Long {
        val f = File(dir(ctx), "$id.json")
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json)
        tmp.renameTo(f)
        return f.lastModified()
    }

    fun save(ctx: Context, d: Doc) {
        val json = d.toJson()
        d.modified = io.submit<Long> { write(ctx, d.id, json) }.get()
        Backup.onSaved()
    }

    fun saveInBackground(ctx: Context, d: Doc) {
        val json = d.toJson()
        val app = ctx.applicationContext
        io.execute {
            val t = write(app, d.id, json)
            main.post { d.modified = t; Backup.onSaved() }
        }
    }

    fun delete(ctx: Context, id: String) =
        io.submit<Boolean> { File(dir(ctx), "$id.json").delete() }.get().also { Backup.onSaved() }
}
