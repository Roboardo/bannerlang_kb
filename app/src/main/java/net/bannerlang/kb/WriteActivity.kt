package net.bannerlang.kb

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import java.io.File

class WriteActivity : Activity(), KeyboardPanel.Host, Backup.Listener {
    private lateinit var panel: KeyboardPanel
    private lateinit var title: TextView
    private lateinit var titleBanners: BannerStripView
    private lateinit var bar: View
    private lateinit var nameBar: View
    private var naming: ((List<Banner>) -> Unit)? = null
    private var wallTokens = emptyList<Token>()
    private var wallCursor = 0
    private val msg get() = panel.msg
    private var doc: Doc? = null
    private var columns = Doc.DEFAULT_COLUMNS
    private var pageLines = 0
    private var pageGap = false
    private var saved = ""
    private lateinit var saveButton: ImageButton
    private val handler = Handler(Looper.getMainLooper())
    private val autosaveRun = Runnable { autosave() }
    private var pendingSince = 0L

    override val isIme = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        panel = KeyboardPanel(this, this, embedPreview = false)
        title = label("", 18f, Ui.TEXT).apply { setPadding(dpi(8), 0, dpi(8), 0) }
        titleBanners = BannerStripView(this, emptyList(), dp(32f))
        bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(4), dpi(4), dpi(4), dpi(4))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { rename() }
                contentDescription = "Wall name"
                addView(titleBanners, lp(WRAP, WRAP))
                addView(title, lp(0, WRAP, 1f))
            }, lp(0, WRAP, 1f))
            fun icon(res: Int, desc: String, onClick: () -> Unit) =
                addView(iconButton(res, desc) { onClick() }, lpIcon().apply { leftMargin = dpi(4) })
            icon(R.drawable.ic_folder, "Walls") { files() }
            saveButton = iconButton(R.drawable.ic_save, "Save") { save { toast("Saved, hold to toggle autosave") } }.apply {
                setOnLongClickListener { toggleAutosave(); true }
            }
            addView(saveButton, lpIcon().apply { leftMargin = dpi(4) })
            icon(R.drawable.ic_share, "Export") { export() }
            icon(R.drawable.ic_grid, "Grid") { pickGrid() }
        }
        nameBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(8), dpi(4), dpi(4), dpi(4))
            visibility = View.GONE
            addView(label("Edit wall name", 18f, Ui.TEXT), lp(0, WRAP, 1f))
            addView(iconButton(R.drawable.ic_close, "Cancel") { endNaming(false) }, lpIcon().apply { leftMargin = dpi(4) })
            addView(iconButton(R.drawable.ic_check, "Done", Ui.ACCENT) { endNaming(true) }, lpIcon().apply { leftMargin = dpi(4) })
        }
        setContentView(column().apply {
            setBackgroundColor(Ui.BG)
            addView(bar, lp(MATCH, WRAP))
            addView(nameBar, lp(MATCH, WRAP))
            addView(panel.messageView, lp(MATCH, 0, 1f))
            addView(panel, lp(MATCH, WRAP))
            padForSystemBars(top = true)
        })
        panel.onChanged = { if (naming == null) { updateTitle(); scheduleAutosave() } }
        showAutosave()
        panel.messageView.setOnClickListener { showKeyboard(true) }
        val d = intent.getStringExtra(OPEN)?.let { Docs.load(this, it) } ?: Docs.list(this).firstOrNull()
        if (d != null && intent.getStringExtra(START) != START_NEW) open(d) else newFile()
    }

    override fun onResume() {
        super.onResume()
        panel.reloadLanguage()
    }

    override fun onPause() {
        super.onPause()
        flushAutosave()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autosaveRun)
        super.onDestroy()
    }

    override fun onSynced() = panel.reloadLanguage()

    private fun showAutosave() {
        saveButton.imageTintList = android.content.res.ColorStateList.valueOf(if (Repo.wallAutosave) Ui.ACCENT else Ui.TEXT)
        saveButton.contentDescription = if (Repo.wallAutosave) "Save (autosave on)" else "Save"
    }

    private fun toggleAutosave() {
        Repo.wallAutosave = !Repo.wallAutosave
        showAutosave()
        toast(when {
            !Repo.wallAutosave -> "Autosave off"
            doc == null -> "Autosave on after the first save"
            else -> "Autosave on"
        })
        if (Repo.wallAutosave) scheduleAutosave() else { handler.removeCallbacks(autosaveRun); pendingSince = 0 }
    }

    private fun scheduleAutosave() {
        if (!Repo.wallAutosave || doc == null || naming != null || !dirty) return
        val now = SystemClock.uptimeMillis()
        if (pendingSince == 0L) pendingSince = now
        handler.removeCallbacks(autosaveRun)
        handler.postDelayed(autosaveRun, minOf(AUTOSAVE_IDLE, AUTOSAVE_MAX - (now - pendingSince)).coerceAtLeast(0))
    }

    private fun autosave() {
        pendingSince = 0
        val d = doc ?: return
        if (!Repo.wallAutosave || naming != null || !dirty) return
        writeDoc(d)
        Docs.saveInBackground(this, d)
        updateTitle()
    }

    private fun flushAutosave() {
        if (pendingSince == 0L) return
        handler.removeCallbacks(autosaveRun)
        autosave()
    }

    private fun snapshot() = Doc("", columns, false, msg.lines(), 0, pageLines, pageGap).toJson()
    private val dirty get() = snapshot() != saved

    private fun updateTitle() {
        titleBanners.banners = doc?.title.orEmpty()
        titleBanners.rtl = doc?.rtl == true
        title.text = if (dirty) "•" else ""
    }

    private fun open(d: Doc?) {
        handler.removeCallbacks(autosaveRun)
        pendingSince = 0
        doc = d
        columns = d?.columns ?: Doc.DEFAULT_COLUMNS
        pageLines = d?.pageLines ?: 0
        pageGap = d?.pageGap ?: false
        panel.closeEditor()
        panel.commitComposing()
        msg.clear()
        if (d != null) msg.insert(Message.tokensOf(d.lines))
        msg.setCursor(msg.tokens.size)
        panel.messageView.columns = columns
        panel.messageView.pageLines = pageLines
        saved = snapshot()
        panel.refresh()
    }

    private fun newFile() = open(null)

    private val keyboardShown get() = panel.visibility == View.VISIBLE

    private fun showKeyboard(show: Boolean) {
        if (show == keyboardShown) return
        if (!show) { panel.commitComposing(); panel.refresh() }
        panel.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun confirmLeave(then: () -> Unit) {
        panel.commitComposing()
        panel.refresh()
        if (!dirty) { then(); return }
        if (Repo.wallAutosave && doc != null && naming == null) { save(then); return }
        AlertDialog.Builder(this)
            .setMessage("Save changes to ${if (doc == null) "the new wall" else "this wall"}?")
            .setPositiveButton("Save") { _, _ -> save(then) }
            .setNeutralButton("Discard") { _, _ -> then() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askName(currentBanners: List<Banner>, onName: (List<Banner>) -> Unit) {
        panel.closeEditor()
        panel.commitComposing()
        val selected = if (msg.hasSelection) msg.tokens.subList(msg.selStart, msg.selEnd).filterIsInstance<Token.B>().map { it.banner }
            else emptyList()
        wallTokens = msg.tokens.toList()
        wallCursor = msg.cursor
        naming = onName
        msg.clear()
        msg.insert(currentBanners.ifEmpty { selected }.map { Token.B(it) })
        panel.messageView.pageLines = 0
        bar.visibility = View.GONE
        nameBar.visibility = View.VISIBLE
        showKeyboard(true)
        panel.refresh()
    }

    private fun endNaming(ok: Boolean) {
        val onName = naming ?: return
        panel.closeEditor()
        panel.commitComposing()
        val banners = msg.tokens.filterIsInstance<Token.B>().map { it.banner }
        naming = null
        msg.clear()
        msg.insert(wallTokens)
        msg.setCursor(wallCursor)
        panel.messageView.columns = columns
        panel.messageView.pageLines = pageLines
        nameBar.visibility = View.GONE
        bar.visibility = View.VISIBLE
        panel.refresh()
        updateTitle()
        if (ok) onName(banners)
    }

    private fun save(then: () -> Unit) {
        panel.commitComposing()
        val d = doc
        if (d == null) {
            askName(emptyList()) { banners ->
                doc = Doc(newId(), columns, false, emptyList(), title = banners)
                save(then)
            }
            return
        }
        handler.removeCallbacks(autosaveRun)
        pendingSince = 0
        writeDoc(d)
        Docs.save(this, d)
        panel.refresh()
        then()
    }

    private fun writeDoc(d: Doc) {
        d.columns = columns
        d.pageLines = pageLines
        d.pageGap = pageGap
        d.rtl = panel.lang?.rtl == true
        d.lines = msg.lines()
        saved = snapshot()
    }

    private fun rename() {
        val d = doc ?: return save {}
        askName(d.title) { banners ->
            d.title = banners
            Docs.list(this).firstOrNull { it.id == d.id }?.let { stored -> stored.title = banners; Docs.save(this, stored) }
            updateTitle()
        }
    }

    private fun files() = WallsDialog.show(this, doc?.id,
        onNew = { confirmLeave { newFile() } },
        onOpen = { d -> confirmLeave { open(Docs.load(this, d.id)) } },
        onDeleted = { d -> if (d.id == doc?.id) { doc = null; saved = ""; updateTitle() } })

    private fun pickGrid() {
        fun picker(min: Int, max: Int, v: Int, off: String? = null) = NumberPicker(this).apply {
            minValue = min; maxValue = max; value = v
            if (off != null) displayedValues = Array(max - min + 1) { if (it == 0) off else "${min + it}" }
            wrapSelectorWheel = false
        }
        val cols = picker(1, 32, columns)
        val lines = picker(0, Doc.MAX_PAGE_LINES, pageLines, "Off")
        val gap = RadioGroup(this).apply {
            addView(RadioButton(context).apply { id = 1; text = "One image per page" })
            addView(RadioButton(context).apply { id = 2; text = "Empty line between pages" })
            check(if (pageGap) 2 else 1)
        }
        fun labeled(title: String, picker: NumberPicker) = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label(title), lp(WRAP, WRAP))
            addView(picker, lp(WRAP, WRAP))
        }
        val exportLabel = label("Export pages as")
        fun showGap() = (lines.value > 0).let { exportLabel.isEnabled = it; for (k in 0 until gap.childCount) gap.getChildAt(k).isEnabled = it }
        lines.setOnValueChangedListener { _, _, _ -> showGap() }
        showGap()
        AlertDialog.Builder(this)
            .setTitle("Grid")
            .setView(column().apply {
                setPadding(dpi(20), dpi(8), dpi(20), 0)
                addView(LinearLayout(context).apply {
                    addView(labeled("Banners per line", cols), lp(0, WRAP, 1f))
                    addView(labeled("Lines per page", lines), lp(0, WRAP, 1f))
                }, lp(MATCH, WRAP))
                addView(exportLabel, lpTop(12))
                addView(gap, lp(MATCH, WRAP))
            })
            .setPositiveButton("OK") { _, _ ->
                columns = cols.value
                pageLines = lines.value
                pageGap = gap.checkedRadioButtonId == 2
                panel.messageView.columns = columns
                panel.messageView.pageLines = pageLines
                updateTitle()
                scheduleAutosave()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun export() {
        panel.commitComposing()
        panel.refresh()
        if (msg.isEmpty) { toast("Nothing to export"); return }
        AlertDialog.Builder(this)
            .setTitle("Export as")
            .setItems(Output.entries.map { it.label }.toTypedArray()) { _, which -> share(Output.entries[which]) }
            .show()
    }

    private fun share(out: Output) {
        val rows = msg.rows(columns, panel.lang?.keepWords == true)
        val rtl = panel.lang?.rtl == true
        val pages = pages(rows, pageLines).let { ps ->
            if (pageGap) listOf(ps.flatMapIndexed { i, p -> if (i > 0) listOf(emptyList<Banner?>()) + p else p })
            else ps.filter { p -> p.any { l -> l.any { it != null } } }.ifEmpty { listOf(rows) }
        }
        val send = Intent(if (pages.size > 1 && out == Output.IMAGE) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
        if (out == Output.IMAGE) {
            val dir = File(cacheDir, ImageProvider.DIR).apply { mkdirs() }
            dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(10)?.forEach { it.delete() }
            val now = System.currentTimeMillis()
            val uris = ArrayList(pages.mapIndexed { i, p ->
                val file = File(dir, "file_${now}_${i + 1}.png")
                BannerRenderer.exportPng(p, file, rtl, Repo.exportScale, columns)
                ImageProvider.uriFor(file)
            })
            send.type = "image/png"
            if (uris.size > 1) send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            else send.putExtra(Intent.EXTRA_STREAM, uris[0])
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            send.type = "text/plain"
            send.putExtra(Intent.EXTRA_TEXT, pages.joinToString("\n") { BannerWriter.imageLink(BannerWriter.Writing(rtl, it)) })
        }
        startActivity(Intent.createChooser(send, "Export wall"))
    }

    @Deprecated("Back handling without AndroidX")
    override fun onBackPressed() {
        if (panel.editorOpen) { panel.closeEditor(); return }
        if (naming != null) { endNaming(false); return }
        if (keyboardShown) { showKeyboard(false); return }
        confirmLeave { finish() }
    }

    override fun openApp() {
        val l = panel.lang
        if (l == null) finish()
        else startActivity(Intent(this, LanguageActivity::class.java).putExtra("id", l.id))
    }

    companion object {
        const val START = "start"
        const val START_NEW = "new"
        const val OPEN = "open"
        private const val AUTOSAVE_IDLE = 1500L
        private const val AUTOSAVE_MAX = 10_000L
    }
}
