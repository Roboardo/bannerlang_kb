package net.bannerlang.kb

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

// simple importer for raw banners exported from banner-writer localStorage
object BannerWriterImport {
    private const val COMMAND = "localStorage.recent + localStorage.saved"

    private fun banners(text: String) =
        BannerWriter.parseStorage(text).flatMap { it.lines.flatten().filterNotNull() }.distinct()

    fun show(ctx: Context) {
        val clip = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        val input = EditText(ctx).apply {
            hint = "Paste the output here"
            minLines = 3
            maxLines = 6
            if (clip != null && banners(clip).isNotEmpty()) setText(clip)
        }
        val box = ctx.column().apply {
            setPadding(ctx.dpi(20), ctx.dpi(8), ctx.dpi(20), 0)
            addView(ctx.label("Open banner-writer.web.app in your browser, open the developer console (F12) and run:"))
            addView(ctx.label(COMMAND, 14f, Ui.TEXT).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }, ctx.lpTop(8))
            addView(ctx.textButton("Copy command") {
                ctx.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Command", COMMAND))
            }, lp(WRAP, WRAP).apply { topMargin = ctx.dpi(8) })
            addView(ctx.label("Then copy the entire output and paste it here:"), ctx.lpTop(8))
            addView(input)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Import from banner-writer")
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Import") { _, _ ->
                val found = banners(input.text.toString())
                if (found.isEmpty()) ctx.toast("No banners found in pasted text.")
                else pickLanguage(ctx) { grid(ctx, it, found) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun pickLanguage(ctx: Context, then: (Language) -> Unit) {
        val langs = Repo.languages
        val create = {
            BannerEditorView.showDialog(ctx, Banner.BLANK, "Language icon") { icon ->
                val l = Language(newId(), icon)
                Repo.languages.add(l)
                Repo.select(l.id)
                then(l)
            }
        }
        if (langs.isEmpty()) { create(); return }
        lateinit var d: AlertDialog
        val row = FlowLayout(ctx).apply {
            setPadding(ctx.dpi(20), ctx.dpi(8), ctx.dpi(20), 0)
            for (l in langs) addView(BannerStripView(ctx, listOf(l.icon), ctx.dp(56f)).apply {
                highlighted = l === Repo.selected()
                contentDescription = "Language"
                setOnClickListener { d.dismiss(); then(l) }
            })
            addView(BannerStripView(ctx, emptyList(), ctx.dp(56f)).apply {
                contentDescription = "New language"
                setOnClickListener { d.dismiss(); create() }
            })
        }
        d = AlertDialog.Builder(ctx)
            .setTitle("Choose import language")
            .setMessage("or create a new one.")
            .setView(ScrollView(ctx).apply { addView(row) })
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun grid(ctx: Context, lang: Language, found: List<Banner>) {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = ctx.column().apply {
            setPadding(ctx.dpi(12), ctx.dpi(12), ctx.dpi(12), ctx.dpi(12))
        }
        lateinit var refresh: () -> Unit
        refresh = {
            body.removeAllViews()
            val words = lang.words.map { it.banners }.toSet()
            val graphemes = lang.graphemes.map { it.banner }.toSet()
            val left = found.filter { listOf(it) !in words || it !in graphemes }
            body.addView(ctx.heading("${left.size} banners",
                if (left.isEmpty()) "Banners already imported."
                else "Tap to add as word or banner."))
            body.addView(FlowLayout(ctx).apply {
                rtl = lang.rtl
                for (b in left) addView(BannerStripView(ctx, listOf(b), ctx.dp(56f)).apply {
                    contentDescription = "Add"
                    setOnClickListener {
                        val items = mutableListOf<Pair<String, () -> Unit>>()
                        if (listOf(b) !in words) items.add("Add as word" to {
                            WordEditor.show(ctx, lang, null, copyOf = Word("", listOf(b), listOf(emptyList()))) { refresh() }
                        })
                        if (b !in graphemes) items.add("Add as banner" to {
                            lang.graphemes.add(Grapheme(newId(), b)); Repo.save(); refresh()
                        })
                        AlertDialog.Builder(ctx)
                            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
                            .show()
                    }
                })
            }, ctx.lpTop(12))
        }
        refresh()
        val bar = LinearLayout(ctx).apply {
            gravity = Gravity.END
            setPadding(ctx.dpi(12), ctx.dpi(8), ctx.dpi(12), ctx.dpi(12))
            addView(ctx.textButton("Done") { d.dismiss() })
        }
        d.setContentView(ctx.column().apply {
            setBackgroundColor(Ui.BG)
            addView(ScrollView(ctx).apply { addView(body) }, lp(MATCH, 0, 1f))
            addView(bar, lp(MATCH, WRAP))
        })
        d.showLarge()
    }
}
