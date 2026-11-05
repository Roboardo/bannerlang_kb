package net.bannerlang.kb

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout

class SettingsActivity : Activity() {
    private lateinit var content: LinearLayout
    private val onTexturesChanged: () -> Unit = { rebuild() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = screen()
    }

    override fun onResume() {
        super.onResume()
        Textures.addListener(onTexturesChanged)
        Backup.onSyncStatus = { rebuild() }
        rebuild()
    }

    override fun onPause() {
        super.onPause()
        Textures.removeListener(onTexturesChanged)
        Backup.onSyncStatus = null
    }

    private fun pickFile(create: Boolean, request: Int) = pickFile(this, create, request)

    @Deprecated("Activity result API needs AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            TEXTURES -> Textures.startInstall(this, uri)
            EXPORT -> try { Backup.write(uri, Backup.export()); toast("Exported") }
                      catch (e: Exception) { toast("Couldn't export: ${e.message}") }
            IMPORT -> importFile(this, uri) {}
            BANNERDB -> importBannerDb(this, uri) {}
            SYNC -> try {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                Backup.startSync(uri)
                rebuild()
            } catch (_: SecurityException) { toast("That app doesn't allow saving there later") }
        }
    }

    private fun section(title: String, sub: String) {
        content.addView(heading(title, sub), lpTop(24))
    }

    private fun options(values: IntArray, selected: Int, name: (Int) -> String, pick: (Int) -> Unit) {
        content.addView(FlowLayout(this, dpi(8)).apply {
            for (v in values) addView(textButton(name(v), accent = v == selected) { pick(v); rebuild() })
        }, lpTop(8))
    }

    private fun rebuild() {
        content.removeAllViews()
        content.addView(screenTitle("Settings"))

        section("Send as", Repo.output.hint + " Hold send on the keyboard to change.")
        options(Output.entries.indices.toList().toIntArray(), Repo.output.ordinal, { Output.entries[it].label }) {
            Repo.output = Output.entries[it]
        }

        section("Banner textures", when {
            Textures.busy -> Textures.status ?: "Reading file…"
            Textures.status != null -> Textures.status!!
            Textures.installed && Textures.builtIn -> "Built in, from Minecraft ${Textures.version}. To use another version, " +
                "pick its game file (client.jar)."
            Textures.installed -> "From Minecraft ${Textures.version}. Pick a newer client.jar to update."
            else -> "Using placeholders. Pick a Minecraft game file (client.jar) to use real textures."
        })
        if (!Textures.busy) {
            content.addView(FlowLayout(this, dpi(8)).apply {
                addView(textButton("Pick client.jar", accent = !Textures.installed) { pickFile(false, TEXTURES) })
            }, lpTop(8))
        }

        val s = Repo.exportScale
        section("Image size", "Banner: ${20 * s}×${40 * s} px")
        options(BannerRenderer.EXPORT_SCALES, s, { if (it == 1) "1× (default)" else "$it×" }) { Repo.exportScale = it }

        section("Message width", "Wider image, smaller banners in chat.")
        options(BannerRenderer.CHAT_WIDTHS, Repo.chatWidth, { if (it == 0) "Fit text" else "$it banners" }) {
            Repo.chatWidth = it
        }

        content.addView(switch(Repo.frequentFirst, "Frequently used words first") {
            Repo.frequentFirst = it; rebuild()
        }.apply { textSize = 18f }, lpTop(24))
        content.addView(label(if (Repo.frequentFirst) "Suggestions prioritize frequently used words"
            else "Suggestions prioritize shorter words"), lpTop(2))

        content.addView(switch(Repo.illegalOutline, "Outline invalid banners") {
            Repo.illegalOutline = it; rebuild()
        }.apply { textSize = 18f }, lpTop(24))
        content.addView(label("Outline banners with more than 6 patterns"), lpTop(2))

        section("Backup", "Languages, banners, words, walls and settings as a file.")
        content.addView(FlowLayout(this, dpi(8)).apply {
            addView(textButton("Export") { pickFile(true, EXPORT) })
            addView(textButton("Import") { pickFile(false, IMPORT) })
            addView(textButton("Import from banner-writer (experimental)") { BannerWriterImport.show(this@SettingsActivity) })
            addView(textButton("Import from bannerdb") { pickFile(false, BANNERDB) })
        }, lpTop(8))

        val syncing = Backup.syncUri != null
        section("Sync to a file", if (syncing) Backup.syncStatus ?: "Saves after every change."
            else "Pick a file in Drive or another storage app to keep it up to date after every change. " +
                "Pick the same file on your other phone to keep both up to date.")
        content.addView(FlowLayout(this, dpi(8)).apply {
            addView(textButton("New file") { pickFile(true, SYNC) })
            addView(textButton("Existing file") { pickFile(false, SYNC) })
            if (syncing) addView(textButton("Stop syncing") {
                Backup.syncUri?.let { u ->
                    try { contentResolver.releasePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
                }
                Backup.stopSync(); rebuild()
            })
        }, lpTop(8))
        if (syncing) content.addView(switch(Backup.mergeChanges, "Merge changes made elsewhere") {
            Backup.setMerge(it); rebuild()
        }, lpTop(8))
        if (syncing) content.addView(label(if (Backup.mergeChanges)
            "Before saving, changes in the file are added here. Asks only when something changed on both sides."
            else "Saves over the file, even if it was changed elsewhere."), lpTop(4))
    }

    companion object {
        const val EXPORT = 1
        const val IMPORT = 2
        const val SYNC = 3
        const val TEXTURES = 4
        const val BANNERDB = 5

        fun pickFile(a: Activity, create: Boolean, request: Int) {
            val i = Intent(if (create) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
            if (create) i.setType("application/json").putExtra(Intent.EXTRA_TITLE, "banners.json") else i.type = "*/*"
            try { a.startActivityForResult(i, request) } catch (_: Exception) { a.toast("No file picker on this phone") }
        }

        fun importFile(a: Activity, uri: Uri, done: () -> Unit) {
            val m = try {
                Backup.Merge(Backup.parse(Backup.read(uri)), Docs.list(a), saveDoc = { Docs.save(a, it) })
            } catch (_: Exception) { null }
            if (m == null) { a.toast("Not a banner keyboard file"); return }
            merge(a, m, done)
        }

        fun importBannerDb(a: Activity, uri: Uri, done: () -> Unit) {
            val text = try { Backup.read(uri) } catch (_: Exception) { a.toast("Couldn't read the file"); return }
            if (!BannerDb.looksLike(text)) { a.toast("Not a bannerdb export"); return }
            BannerWriterImport.pickLanguage(a) { l ->
                val c = try { BannerDb.parse(text, l) } catch (_: Exception) { null }
                if (c == null) a.toast("No banners in that file")
                else merge(a, Backup.Merge(c, Docs.list(a), saveDoc = { Docs.save(a, it) }), done)
            }
        }

        private fun merge(a: Activity, m: Backup.Merge, done: () -> Unit) {
            if (!m.hasChanges) { a.toast("Nothing new"); return }
            if (!m.hasConflicts) { m.apply(false); a.toast("Imported"); done(); return }
            m.ask(a, "Import", cancelable = true) { a.toast("Imported"); done() }
        }
    }
}
