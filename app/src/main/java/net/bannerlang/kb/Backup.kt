package net.bannerlang.kb

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

object Backup {
    private lateinit var ctx: Context
    private lateinit var syncConfig: File
    private val writer = Executors.newSingleThreadExecutor()
    private val pending = AtomicReference<String?>(null)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var baseFile: File
    @Volatile private var base: String? = null
    private lateinit var writtenFile: File
    private val written = mutableListOf<String>()
    private var resumed: Activity? = null
    private var lastCheck = 0L
    private var needsChoice = false
    private var choosing = false

    var syncUri: Uri? = null
        private set
    @Volatile var mergeChanges = true
        private set
    var syncStatus: String? = null
        private set
    var onSyncStatus: (() -> Unit)? = null

    fun init(c: Context) {
        ctx = c.applicationContext
        syncConfig = File(ctx.filesDir, "sync.txt")
        baseFile = File(ctx.filesDir, "sync_base.json")
        val config = syncConfig.takeIf { it.exists() }?.readLines()?.map { it.trim() } ?: emptyList()
        syncUri = config.firstOrNull()?.ifEmpty { null }?.let(Uri::parse)
        mergeChanges = "nomerge" !in config
        base = baseFile.takeIf { it.exists() }?.readText()
        writtenFile = File(ctx.filesDir, "sync_written.txt")
        if (writtenFile.exists()) written.addAll(writtenFile.readLines().filter { it.isNotBlank() })
        (ctx as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { resumed = a; check() }
            override fun onActivityPaused(a: Activity) { if (resumed === a) resumed = null }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun enc(b: Banner) = bannerCode(b)
    private fun dec(s: String) = parseBannerCode(s)

    class Contents(val languages: List<Language>, val docs: List<Doc>, val settings: JSONObject? = null)

    fun export(docs: List<Doc> = Docs.list(ctx)): String = JSONObject().put("languages", JSONArray().apply {
        for (l in Repo.languages) {
            val index = l.graphemes.withIndex().associate { it.value.id to it.index }
            put(JSONObject().apply {
                put("icon", enc(l.icon))
                if (l.rtl) put("rtl", true)
                if (l.keepWords) put("keepWords", true)
                if (l.input != InputMode.STANDARD) put("input", l.input.key)
                put("graphemes", JSONArray(l.graphemes.map { enc(it.banner) }))
                if (l.groups.isNotEmpty()) l.ungroupedIcon?.let { put("ungroupedIcon", enc(it)) }
                if (l.groups.isNotEmpty()) put("groups", JSONArray().apply {
                    for (g in l.groups) put(JSONObject().apply {
                        g.icon?.let { put("icon", enc(it)) }
                        put("graphemes", JSONArray(l.inGroup(g).map { index.getValue(it.id) }))
                    })
                })
                put("words", JSONArray().apply {
                    for (w in l.words) put(JSONObject().apply {
                        put("banners", JSONArray(w.banners.map { enc(it) }))
                        if (w.spellings.isNotEmpty())
                            put("spellings", JSONArray(w.spellings.map { s -> JSONArray(s.mapNotNull { index[it] }) }))
                    })
                })
            })
        }
    }).put("files", JSONArray(docs.map { it.toJsonObject() })).put("settings", Repo.settingsToJson()).toString(1)

    fun parse(text: String): Contents {
        val root = JSONObject(text)
        val arr = root.getJSONArray("languages")
        val fs = root.optJSONArray("files") ?: JSONArray()
        val docs = List(fs.length()) { Doc.fromJson(newId(), fs.getJSONObject(it).toString(), System.currentTimeMillis()) }
        return Contents(List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val l = Language(newId(), dec(o.getString("icon")), rtl = o.optBoolean("rtl"), keepWords = o.optBoolean("keepWords"))
            val gs = o.optJSONArray("graphemes") ?: JSONArray()
            for (j in 0 until gs.length()) l.graphemes.add(Grapheme(newId(), dec(gs.getString(j))))
            l.input = InputMode.entries.find { it.key == o.optString("input") } ?: InputMode.STANDARD
            l.ungroupedIcon = o.optString("ungroupedIcon").ifEmpty { null }?.let { dec(it) }
            val grs = o.optJSONArray("groups") ?: JSONArray()
            for (j in 0 until grs.length()) {
                val gr = grs.getJSONObject(j)
                val g = Group(newId(), gr.optString("icon").ifEmpty { null }?.let { dec(it) })
                l.groups.add(g)
                val m = gr.optJSONArray("graphemes") ?: JSONArray()
                for (k in 0 until m.length()) l.graphemes.getOrNull(m.getInt(k))?.group = g.id
            }
            val ws = o.optJSONArray("words") ?: JSONArray()
            for (j in 0 until ws.length()) {
                val w = ws.getJSONObject(j)
                val b = w.getJSONArray("banners")
                val s = w.optJSONArray("spellings") ?: JSONArray()
                val spellings = List(s.length()) { k ->
                    val a = s.getJSONArray(k)
                    List(a.length()) { a.getInt(it) }.mapNotNull { l.graphemes.getOrNull(it)?.id }
                }.filter { it.isNotEmpty() }
                l.words.add(Word(newId(), List(b.length()) { dec(b.getString(it)) }, spellings))
            }
            l
        }, docs, root.optJSONObject("settings"))
    }

    class Merge(
        incoming: Contents, currentDocs: List<Doc>, base: Contents? = null,
        private val saveDoc: (Doc) -> Unit, private val deleteDoc: (Doc) -> Unit = {}
    ) {
        var newLanguages = 0; private set
        var newFiles = 0; private set
        var newGraphemes = 0; private set
        var newGroups = 0; private set
        var newWords = 0; private set
        var changedWords = 0; private set
        var changedDirection = 0; private set
        var changedInput = 0; private set
        var changedWrap = 0; private set
        var changedSettings = 0; private set
        private val adds = mutableListOf<() -> Unit>()
        private val overwrites = mutableListOf<() -> Unit>()

        val hasChanges get() = adds.isNotEmpty() || overwrites.isNotEmpty()
        val hasConflicts get() = overwrites.isNotEmpty()

        private fun <T> pick(cur: T, inc: T, old: T?, count: () -> Unit, take: () -> Unit) {
            if (cur == inc || (old != null && inc == old)) return
            if (old != null && cur == old) adds.add(take) else { count(); overwrites.add(take) }
        }

        private fun spelled(l: Language, ss: List<List<String>>) = ss.map { s -> s.map { l.grapheme(it)?.banner } }.toSet()

        init {
            val baseDocs = base?.docs?.map { it.toJson() }?.toSet() ?: emptySet()
            val incDocs = incoming.docs.map { it.toJson() }.toSet()
            val have = currentDocs.map { it.toJson() }.toMutableSet()
            for (d in incoming.docs) { val j = d.toJson(); if (j !in baseDocs && have.add(j)) { newFiles++; adds.add { saveDoc(d) } } }
            for (d in currentDocs) { val j = d.toJson(); if (j in baseDocs && j !in incDocs) adds.add { deleteDoc(d) } }

            incoming.settings?.let { inc ->
                val cur = Repo.settingsToJson()
                for (k in inc.keys()) {
                    if (!cur.has(k)) continue
                    val v = inc.get(k)
                    pick(cur.get(k).toString(), v.toString(), base?.settings?.opt(k)?.toString(), { changedSettings++ }) {
                        Repo.readSettings(JSONObject().put(k, v))
                    }
                }
            }

            for (inc in incoming.languages) {
                val old = base?.languages?.firstOrNull { it.icon == inc.icon }
                val cur = Repo.languages.firstOrNull { it.icon == inc.icon }
                if (cur == null) { if (old == null) { newLanguages++; adds.add { Repo.languages.add(inc) } }; continue }
                pick(cur.rtl, inc.rtl, old?.rtl, { changedDirection++ }) { cur.rtl = inc.rtl }
                pick(cur.input, inc.input, old?.input, { changedInput++ }) { cur.input = inc.input }
                pick(cur.keepWords, inc.keepWords, old?.keepWords, { changedWrap++ }) { cur.keepWords = inc.keepWords }

                val takeGroups = cur.groups.isEmpty() && inc.groups.isNotEmpty()
                if (takeGroups) {
                    newGroups += inc.groups.size
                    adds.add { cur.groups.addAll(inc.groups); if (cur.ungroupedIcon == null) cur.ungroupedIcon = inc.ungroupedIcon }
                }
                val ids = HashMap<String, String>()
                for (g in inc.graphemes) {
                    val same = cur.graphemes.firstOrNull { it.banner == g.banner }
                    if (!takeGroups) g.group = null
                    if (same != null) {
                        ids[g.id] = same.id
                        val group = g.group
                        if (group != null) adds.add { same.group = group }
                    } else if (old?.graphemes?.any { it.banner == g.banner } != true) {
                        ids[g.id] = g.id; newGraphemes++; adds.add { cur.graphemes.add(g) }
                    }
                }
                for (w in inc.words) {
                    val spellings = w.spellings.mapNotNull { s -> s.map { ids[it] ?: return@mapNotNull null } }.distinct()
                    val oldWord = old?.words?.firstOrNull { it.banners == w.banners }
                    val same = cur.words.firstOrNull { it.banners == w.banners }
                    if (same == null) {
                        if (oldWord != null) continue
                        newWords++
                        adds.add { cur.words.add(Word(w.id, w.banners, spellings)) }
                    } else pick(spelled(cur, same.spellings), spelled(inc, w.spellings),
                                old?.let { o -> oldWord?.let { spelled(o, it.spellings) } }, { changedWords++ }) { same.spellings = spellings }
                }
                if (old != null) for (w in cur.words) {
                    if (inc.words.any { it.banners == w.banners }) continue
                    val oldWord = old.words.firstOrNull { it.banners == w.banners } ?: continue
                    if (spelled(old, oldWord.spellings) == spelled(cur, w.spellings)) adds.add { cur.words.remove(w) }
                }
            }
        }

        fun describe(): String {
            val found = listOfNotNull(
                "$newLanguages new language(s)".takeIf { newLanguages > 0 },
                "$newGraphemes new banner(s)".takeIf { newGraphemes > 0 },
                "$newGroups new group(s)".takeIf { newGroups > 0 },
                "$newWords new word(s)".takeIf { newWords > 0 },
                "$newFiles new wall(s)".takeIf { newFiles > 0 },
            ).joinToString(", ")
            val differ = listOfNotNull(
                "$changedWords word(s) have other spellings".takeIf { changedWords > 0 },
                "$changedDirection language(s) have another writing direction".takeIf { changedDirection > 0 },
                "$changedInput language(s) have another banner input".takeIf { changedInput > 0 },
                "$changedWrap language(s) have another line wrapping".takeIf { changedWrap > 0 },
                "$changedSettings setting(s) are different".takeIf { changedSettings > 0 },
            ).joinToString(", ")
            return (if (found.isEmpty()) "" else "Adds $found.\n\n") + "In the file, $differ. Use the file's version?"
        }

        fun ask(a: Context, title: String, cancelable: Boolean, done: () -> Unit) {
            val d = AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(describe())
                .setPositiveButton("Use file's") { _, _ -> done(); apply(true) }
                .setNeutralButton("Keep mine") { _, _ -> done(); apply(false) }
                .setCancelable(cancelable)
            if (cancelable) d.setNegativeButton("Cancel", null)
            d.show()
        }

        fun apply(overwrite: Boolean) {
            adds.forEach { it() }
            if (overwrite) overwrites.forEach { it() }
            Repo.save()
        }
    }

    interface Listener { fun onSynced() }

    fun startSync(uri: Uri) {
        syncUri = uri
        syncStatus = null
        saveConfig()
        setBase(null)
        onSaved()
    }

    fun stopSync() {
        syncUri = null
        syncConfig.delete()
        setBase(null)
        synchronized(written) { written.clear(); writtenFile.delete() }
        syncStatus = null
    }

    fun setMerge(on: Boolean) {
        mergeChanges = on
        saveConfig()
        lastCheck = 0
        check()
    }

    private fun saveConfig() {
        syncConfig.writeText(syncUri.toString() + if (mergeChanges) "" else "\nnomerge")
    }

    private fun setBase(text: String?) {
        base = text
        if (text == null) baseFile.delete() else baseFile.writeText(text)
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    // a stale copy read back from storage isn't a change made elsewhere.
    private fun wrote(text: String) = synchronized(written) {
        written.add(hash(text))
        while (written.size > 8) written.removeAt(0)
        writtenFile.writeText(written.joinToString("\n"))
    }
    private fun isOurs(text: String) = synchronized(written) { hash(text) in written }

    fun check() {
        if (syncUri == null || !mergeChanges) return
        val now = System.currentTimeMillis()
        if (!needsChoice && now - lastCheck < 15_000) return
        lastCheck = now
        onSaved()
    }

    fun onSaved() {
        val uri = syncUri ?: return
        pending.set(export())
        writer.execute {
            val text = pending.getAndSet(null) ?: return@execute
            fun status(s: String) = main.post { syncStatus = s; onSyncStatus?.invoke() }
            val remote = if (!mergeChanges) null else try { read(uri) } catch (e: Exception) {
                pending.compareAndSet(null, text)
                status("Couldn't read the file: ${e.message ?: e.javaClass.simpleName}")
                return@execute
            }
            if (remote != null && remote.isNotBlank() && remote != base && !isOurs(remote)) {
                main.post { merge(remote) }
                return@execute
            }
            if (remote == text) { setBase(text); return@execute }
            try {
                write(uri, text)
                wrote(text)
                setBase(text)
                status("Saved " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()))
            } catch (e: Exception) {
                status("Couldn't save: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun merge(remote: String) {
        if (choosing || syncUri == null) return
        val incoming = try { parse(remote) } catch (_: Exception) {
            syncStatus = "The file isn't a banner keyboard file, so it's left as it is."
            onSyncStatus?.invoke()
            return
        }
        val old = base?.let { try { parse(it) } catch (_: Exception) { null } }
        val m = Merge(incoming, Docs.list(ctx), old, { Docs.save(ctx, it) }, { Docs.delete(ctx, it.id) })
        val done = {
            needsChoice = false
            setBase(remote)
            if (m.hasChanges) main.post {
                ctx.toast("Updated from the sync file")
                (resumed as? Listener)?.onSynced()
            }
        }
        if (!m.hasConflicts) { done(); m.apply(false); return }
        val a = resumed
        if (a == null) {
            needsChoice = true
            syncStatus = "Changed here and in the file. Open the app to choose."
            onSyncStatus?.invoke()
            return
        }
        choosing = true
        m.ask(a, "Sync", cancelable = false) { choosing = false; done() }
    }

    fun write(uri: Uri, text: String) {
        // workaround, as not every storage app supports "wt" (truncate)
        val out = try { ctx.contentResolver.openOutputStream(uri, "wt") }
                  catch (_: Exception) { ctx.contentResolver.openOutputStream(uri, "w") }
        (out ?: throw java.io.IOException("Can't open the file")).use { it.write(text.toByteArray()) }
    }

    fun read(uri: Uri): String =
        (ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Can't open the file"))
            .use { it.readBytes().toString(Charsets.UTF_8) }
}
