package net.bannerlang.kb

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.LinearLayout
import android.widget.ScrollView

class LanguageActivity : Activity(), Backup.Listener {
    private lateinit var content: LinearLayout
    private lateinit var lang: Language
    private var keyboardMode = false
    private var wordGrid = false
    private var previewGroup: String? = null
    private val filter = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lang = Repo.language(intent.getStringExtra("id")) ?: run { finish(); return }
        content = screen()
        rebuild()
    }

    override fun onSynced() = rebuild()

    private fun changed() {
        Repo.save()
        rebuild()
    }

    private fun rebuild() {
        content.removeAllViews()

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(BannerStripView(this, listOf(lang.icon), dp(72f)).apply {
            contentDescription = "Language icon"
            setOnClickListener {
                BannerEditorView.showDialog(this@LanguageActivity, lang.icon) { lang.icon = it; changed() }
            }
        })
        val isSelected = Repo.selected() === lang
        header.addView(
            if (isSelected) label("Used by keyboard", 14f, Ui.ACCENT)
            else textButton("Use on keyboard") { Repo.select(lang.id); rebuild() },
            lp(0, WRAP, 1f).apply { leftMargin = dpi(12) })
        header.addView(iconButton(R.drawable.ic_settings, "Language settings") { settingsDialog() }, lpIcon())
        content.addView(header)

        if (lang.graphemes.isEmpty()) keyboardMode = false
        filter.retainAll(lang.graphemes.map { it.id }.toSet())
        val keyboardToggle = if (lang.graphemes.isEmpty()) null
            else textButton("Keyboard", accent = keyboardMode) {
                keyboardMode = !keyboardMode
                filter.clear()
                rebuild()
            }
        content.addView(sectionHeader("Banners (${lang.graphemes.size})", when {
            lang.graphemes.isEmpty() -> "Banners are the keys of the keyboard. Add one with +."
            keyboardMode -> "Hold a key and drag to reorder"
            else -> "Tap to filter, hold for options"
        }, keyboardToggle) {
            BannerEditorView.showDialog(this, Banner.BLANK) { b ->
                lang.graphemes.add(Grapheme(newId(), b)); changed()
            }
        })
        if (!keyboardMode) {
            for (gr in listOf<Group?>(null) + lang.groups) {
                val members = lang.inGroup(gr)
                if (gr == null && members.isEmpty() && lang.groups.isNotEmpty()) continue
                if (lang.groups.isNotEmpty()) content.addView(groupHeader(gr, members.size))
                if (members.isEmpty()) continue
                val flow = FlowLayout(this).apply { rtl = lang.rtl }
                for (g in members) {
                    val i = lang.graphemes.indexOf(g)
                    flow.addView(BannerStripView(this, listOf(g.banner), dp(56f)).apply {
                        contentDescription = "Banner ${i + 1}"
                        highlighted = g.id in filter
                        setOnClickListener {
                            if (!filter.remove(g.id)) filter.add(g.id)
                            rebuild()
                        }
                        setOnLongClickListener { graphemeMenu(i); true }
                    })
                }
                content.addView(flow, lpTop(8))
            }
            if (lang.graphemes.isNotEmpty()) content.addView(textButton("New group") {
                lang.groups.add(Group(newId())); changed()
            }, lp(WRAP, WRAP).apply { topMargin = dpi(8) })
        } else {
            val pages = lang.pages()
            val tabbed = pages.size > 1
            val shown = pages.indexOfFirst { it.group?.id == previewGroup }.coerceAtLeast(0)
            if (tabbed) content.addView(GroupTabsView(this) { i -> previewGroup = pages[i].group?.id; rebuild() }.apply {
                set(pages.map { it.icon }, shown, lang.rtl)
            }, lp(MATCH, dpi(GROUP_TABS_HEIGHT)).apply { topMargin = dpi(8) })
            val keys = if (tabbed) pages[shown].keys else lang.graphemes.toList()
            content.addView(KeyGridView(this) { g -> graphemeMenu(lang.graphemes.indexOf(g)) }.apply {
                asKeyboardPreview = true
                if (tabbed) reservedHeight = dpi(GROUP_TABS_HEIGHT)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Ui.BG); cornerRadius = dp(8f); setStroke(dpi(1), Ui.KEY)
                }
                rtl = lang.rtl
                this.keys = keys
                onReorder = { from, to -> lang.reorder(keys, from, to); changed() }
            }, lpTop(if (tabbed) 4 else 8))
        }

        val seq = filter.toList()
        fun startsWithSeq(w: Word) = w.spellings.any { it.size >= seq.size && it.subList(0, seq.size) == seq }
        val words = (if (filter.isEmpty()) lang.words
                     else lang.words.filter { w -> w.spellings.any { it.containsAll(filter) } })
            .sortedWith(compareBy({ seq.isNotEmpty() && !startsWithSeq(it) }, { it.shortest }))
        content.addView(sectionHeader(
            if (filter.isEmpty()) "Words (${lang.words.size})" else "Words (${words.size} of ${lang.words.size})",
            if (lang.graphemes.isEmpty()) "Add banners first to spell words." else "Tap to edit, hold for options",
            if (lang.words.isEmpty()) null
            else if (wordGrid) iconButton(R.drawable.ic_list, "Show as list") { wordGrid = false; rebuild() }
            else iconButton(R.drawable.ic_grid, "Show as grid") { wordGrid = true; rebuild() },
            if (lang.graphemes.isEmpty()) null else ({ WordEditor.show(this, lang, null) { rebuild() } })))
        if (filter.isNotEmpty() && words.isEmpty())
            content.addView(label("No words with all the selected banners."), lpTop(8))
        if (wordGrid) {
            content.addView(FlowLayout(this).apply {
                rtl = lang.rtl
                for (w in words) addView(BannerStripView(context, w.banners, dp(48f)).apply {
                    rtl = lang.rtl
                    background = Ui.bg(Ui.SURFACE, dp(8f))
                    setOnClickListener { WordEditor.show(this@LanguageActivity, lang, w) { rebuild() } }
                    setOnLongClickListener { wordMenu(w); true }
                })
            }, lpTop(6))
        } else for (w in words) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.bg(Ui.SURFACE, dp(8f))
                setPadding(dpi(6), dpi(4), dpi(6), dpi(4))
                setOnClickListener { WordEditor.show(this@LanguageActivity, lang, w) { rebuild() } }
                setOnLongClickListener { wordMenu(w); true }
            }
            row.addView(BannerStripView(this, w.banners, dp(48f)).apply { rtl = lang.rtl })
            row.addView(label("=", 18f), lp(WRAP, WRAP).apply { leftMargin = dpi(8); rightMargin = dpi(8) })
            row.addView(column().apply {
                if (w.spellings.isEmpty()) addView(label("No spelling", 13f))
                for (s in w.spellings)
                    addView(BannerStripView(context, s.mapNotNull { lang.grapheme(it)?.banner }, dp(28f)).apply { rtl = lang.rtl })
            })
            content.addView(row, lpTop(6))
        }
    }

    private fun sectionHeader(title: String, sub: String, extra: View? = null, onAdd: (() -> Unit)?) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(heading(title, sub), lp(0, WRAP, 1f))
        if (extra != null) addView(extra, (if (extra is ImageButton) lpIcon() else lp(WRAP, WRAP)).apply { rightMargin = dpi(4) })
        if (onAdd != null) addView(iconButton(R.drawable.ic_add, "Add", Ui.ACCENT) { onAdd() }, lpIcon())
        layoutParams = lpTop(24)
    }

    private fun settingsDialog() {
        val body = column().apply {
            setPadding(dpi(16), dpi(4), dpi(16), dpi(8))
        }
        lateinit var dialog: AlertDialog
        fun fill() {
            body.removeAllViews()
            fun set(change: () -> Unit) { change(); changed(); fill() }
            body.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label("Writing direction", 14f, Ui.TEXT), lp(0, WRAP, 1f))
                addView(textButton(if (lang.rtl) "← Right to left" else "Left to right →") {
                    set { lang.rtl = !lang.rtl }
                })
            }, lpTop(12))
            body.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(heading("Keep words together", "On a wall, a word that doesn't fit moves to the next line"), lp(0, WRAP, 1f))
                addView(switch(lang.keepWords) { on -> set { lang.keepWords = on } }, lp(WRAP, WRAP))
            }, lpTop(12))
            body.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(heading("Banner input", when (lang.input) {
                    InputMode.STANDARD -> "One banner per key"
                    InputMode.LAYERING -> "Patterns stack on one banner, up to ${Patterns.LOOM_LAYERS}"
                }), lp(0, WRAP, 1f))
                addView(inputModeSpinner { set(it) }, lp(WRAP, WRAP))
            }, lpTop(12))
            body.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label("Delete language", 14f, Ui.TEXT), lp(0, WRAP, 1f))
                addView(iconButton(R.drawable.ic_delete, "Delete language") {
                    AlertDialog.Builder(this@LanguageActivity)
                        .setMessage("Delete language? (${lang.graphemes.size} banners, ${lang.words.size} words)")
                        .setPositiveButton("Delete") { _, _ ->
                            Repo.languages.remove(lang); Repo.save(); dialog.dismiss(); finish()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }, lpIcon())
            }, lpTop(24))
        }
        fill()
        dialog = AlertDialog.Builder(this)
            .setTitle("Language settings")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Done", null)
            .show()
    }

    private fun inputModeSpinner(set: (() -> Unit) -> Unit) = Spinner(this).apply {
        val modes = InputMode.entries
        adapter = object : ArrayAdapter<InputMode>(this@LanguageActivity, 0, modes) {
            private fun row(i: Int, dropDown: Boolean) = label(modes[i].label, 14f, Ui.TEXT).apply {
                setPadding(dpi(12), dpi(if (dropDown) 12 else 8), dpi(12), dpi(if (dropDown) 12 else 8))
            }
            override fun getView(i: Int, convertView: View?, parent: ViewGroup) = row(i, false)
            override fun getDropDownView(i: Int, convertView: View?, parent: ViewGroup) = row(i, true)
        }
        setPopupBackgroundDrawable(Ui.bg(Ui.SURFACE, dp(8f)))
        setSelection(modes.indexOf(lang.input), false)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) {
                if (modes[i] != lang.input) set { lang.input = modes[i] }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun groupName(g: Group?) = if (g == null) "No group" else "Group ${lang.groups.indexOf(g) + 1}"

    private fun groupHeader(g: Group?, count: Int) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val name = groupName(g)
        lang.groupIcon(g)?.let { addView(BannerStripView(context, listOf(it), dp(32f)), lp(WRAP, WRAP).apply { rightMargin = dpi(8) }) }
        addView(label("$name ($count)", 14f, Ui.TEXT), lp(0, WRAP, 1f))
        setOnClickListener { groupMenu(g, count) }
        if (g != null) addView(iconButton(R.drawable.ic_add, "Add to $name") {
            BannerEditorView.showDialog(this@LanguageActivity, Banner.BLANK) { b ->
                lang.graphemes.add(Grapheme(newId(), b, g.id)); changed()
            }
        }, lpIcon())
        layoutParams = lpTop(16)
    }

    private fun setGroupIcon(g: Group?, icon: Banner?) {
        if (g == null) lang.ungroupedIcon = icon else g.icon = icon
        changed()
    }

    private fun groupMenu(g: Group?, count: Int) {
        val i = lang.groups.indexOf(g)
        val icon = lang.groupIcon(g)
        val items = listOfNotNull(
            (if (icon == null) "Set icon" else "Change icon") to {
                BannerEditorView.showDialog(this, icon ?: Banner.BLANK) { setGroupIcon(g, it) }
            },
            icon?.let { "Remove icon" to { setGroupIcon(g, null) } },
            if (count > 0) "Clone as new group" to { lang.cloneGroup(g); changed() } else null,
            if (count > 0) "Change colors" to { recolorDialog(g) } else null,
            if (g != null && i > 0) "Move up" to { lang.groups.add(i - 1, lang.groups.removeAt(i)); changed() } else null,
            if (g != null && i < lang.groups.size - 1) "Move down" to { lang.groups.add(i + 1, lang.groups.removeAt(i)); changed() } else null,
            if (g != null) "Delete group" to {
                AlertDialog.Builder(this)
                    .setMessage("Delete group? Its banners are kept, without a group.")
                    .setPositiveButton("Delete") { _, _ ->
                        lang.graphemes.filter { it.group == g.id }.forEach { it.group = null }
                        lang.groups.remove(g); changed()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                Unit
            } else null,
        )
        AlertDialog.Builder(this)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun recolorDialog(g: Group?) {
        val keys = lang.inGroup(g)
        val work = keys.map { it.banner }.toMutableList()
        val preview = FlowLayout(this).apply { rtl = lang.rtl }
        fun used() = work.flatMap { it.colors() }.toSet()
        var from = work.flatMap { b -> b.layers.map { it.color } + b.base }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: DyeColors.WHITE
        var to = from
        lateinit var fromPalette: PaletteView
        lateinit var toPalette: PaletteView
        var replace: android.widget.Button? = null
        fun refresh() {
            fromPalette.selected = from
            toPalette.selected = to
            preview.removeAllViews()
            for (b in work) preview.addView(BannerStripView(this, listOf(b), dp(40f)).apply {
                highlighted = from in b.colors()
            })
            replace?.isEnabled = from != to && from in used()
        }
        fromPalette = PaletteView(this) { c -> from = c; refresh() }
        toPalette = PaletteView(this) { c -> to = c; refresh() }
        val body = column().apply {
            setPadding(dpi(16), dpi(12), dpi(16), 0)
            addView(label("Change this color", 14f, Ui.TEXT))
            addView(fromPalette, lpTop(4))
            addView(label("Into", 14f, Ui.TEXT), lpTop(12))
            addView(toPalette, lpTop(4))
            addView(label("Highlighted banners use the first color. Replace as many times as needed, then save.", 13f),
                lpTop(12))
            addView(ScrollView(context).apply { addView(preview) }, lp(MATCH, dpi(160)).apply { topMargin = dpi(8) })
        }
        refresh()
        val d = AlertDialog.Builder(this)
            .setTitle("Colors of ${groupName(g)}")
            .setView(body)
            .setPositiveButton("Save") { _, _ ->
                keys.forEachIndexed { i, k -> k.banner = work[i] }
                changed()
            }
            .setNeutralButton("Replace", null)
            .setNegativeButton("Cancel", null)
            .show()
        replace = d.getButton(AlertDialog.BUTTON_NEUTRAL)
        replace?.setOnClickListener {
            for (i in work.indices) work[i] = work[i].swapColor(from, to)
            from = to
            refresh()
        }
        refresh()
    }

    private fun moveToGroup(g: Grapheme) {
        val choices = listOf<Group?>(null) + lang.groups
        val names = choices.map { if (it == null) "No group" else "Group ${lang.groups.indexOf(it) + 1}" }
        val current = choices.indexOfFirst { it?.id == g.group }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Move to group")
            .setSingleChoiceItems(names.toTypedArray(), current) { d, which ->
                g.group = choices[which]?.id; d.dismiss(); changed()
            }
            .show()
    }

    private fun wordMenu(w: Word) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Copy as new word", "Delete")) { _, which ->
                if (which == 0) WordEditor.show(this, lang, null, copyOf = w) { rebuild() }
                else AlertDialog.Builder(this)
                    .setMessage("Delete word?")
                    .setPositiveButton("Delete") { _, _ -> lang.words.remove(w); changed() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .show()
    }

    private fun graphemeMenu(i: Int) {
        val g = lang.graphemes[i]
        val items = listOfNotNull("Edit", "Copy as new word", "Move to group".takeIf { lang.groups.isNotEmpty() }, "Delete")
        AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Edit" -> BannerEditorView.showDialog(this, g.banner) { g.banner = it; changed() }
                    "Copy as new word" -> WordEditor.show(this, lang, null, copyOf = Word("", listOf(g.banner), listOf(listOf(g.id)))) { rebuild() }
                    "Move to group" -> moveToGroup(g)
                    else -> {
                        val used = lang.words.count { w -> w.spellings.any { g.id in it } }
                        AlertDialog.Builder(this)
                            .setMessage(if (used > 0) "Delete banner? $used word(s) are spelled with it."
                                        else "Delete banner?")
                            .setPositiveButton("Delete") { _, _ -> lang.graphemes.remove(g); changed() }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .show()
    }
}

fun android.content.Context.toast(msg: String) =
    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
