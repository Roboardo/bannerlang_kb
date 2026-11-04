package net.bannerlang.kb

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView

object WordEditor {
    fun show(ctx: Context, lang: Language, word: Word?, copyOf: Word? = null, onChanged: () -> Unit) {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(WordEditorView(ctx, lang, word, copyOf) { changed ->
            d.dismiss()
            if (changed) onChanged()
        })
        d.showLarge()
    }
}

@SuppressLint("ViewConstructor")
class WordEditorView(
    ctx: Context,
    private val lang: Language,
    private val word: Word?,
    copyOf: Word? = null,
    private val onInsert: ((List<Banner>) -> Unit)? = null,
    private val onDone: (Boolean) -> Unit
) : FrameLayout(ctx) {
    private var asWord = word != null || onInsert == null || copyOf?.spellings?.any { it.isNotEmpty() } == true
    private val banners = (word ?: copyOf)?.banners?.toMutableList() ?: mutableListOf()
    private val spellings = (word ?: copyOf)?.spellings?.map { it.toMutableList() }?.toMutableList()
        ?: mutableListOf(mutableListOf())
    private var current = if (spellings.isEmpty()) -1 else 0
    private var cursor = spellings.firstOrNull()?.size ?: 0
    private var selected = -1
    private var confirmDelete = false

    private val main = ctx.column()
    private val bannerFlow = FlowLayout(ctx).apply { rtl = lang.rtl }
    private val bannerActions = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
    private val spellingList = ctx.column()
    private val headingHolder = FrameLayout(ctx)
    private val wordPart = ctx.column()
    private val bar = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(12), dpi(8), dpi(12), dpi(12))
    }

    init {
        setBackgroundColor(Ui.BG)
        val body = ctx.column().apply {
            setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
        }
        body.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(headingHolder, lp(0, WRAP, 1f))
            if (word == null && onInsert != null) addView(ctx.switch(asWord) { asWord = it; refresh() }.apply {
                contentDescription = "Save as a word"
            }, lp(WRAP, WRAP).apply { rightMargin = ctx.dpi(8) })
            addView(ctx.iconButton(R.drawable.ic_paste, "Paste banners") { paste() }, ctx.lpIcon())
        })
        body.addView(bannerFlow, lpTop(8))
        body.addView(bannerActions, lpTop(6))

        body.addView(wordPart, lp(MATCH, WRAP))
        wordPart.addView(ctx.heading("Spellings",
            "Typing any spelling shows this word. Without one it's never suggested."),
            lpTop(24))
        wordPart.addView(spellingList, lpTop(2))
        wordPart.addView(ctx.textButton("Add another spelling") {
            spellings.add(mutableListOf()); current = spellings.size - 1; cursor = 0; refresh()
        }, lp(WRAP, WRAP).apply { topMargin = dpi(8) })
        val pages = lang.pages()
        val pickerKeys = FlowLayout(ctx).apply { rtl = lang.rtl }
        fun showKeys(keys: List<Grapheme>) {
            pickerKeys.removeAllViews()
            for (g in keys) pickerKeys.addView(BannerStripView(ctx, listOf(g.banner), ctx.dp(44f)).apply {
                setOnClickListener {
                    if (current < 0) { spellings.add(mutableListOf()); current = spellings.size - 1; cursor = 0 }
                    spellings[current].add(cursor++, g.id)
                    refresh()
                }
            })
        }
        if (pages.size > 1) {
            lateinit var tabs: GroupTabsView
            tabs = GroupTabsView(ctx) { i -> tabs.set(pages.map { it.icon }, i, lang.rtl); showKeys(pages[i].keys) }
            tabs.set(pages.map { it.icon }, 0, lang.rtl)
            wordPart.addView(tabs, lp(MATCH, dpi(GROUP_TABS_HEIGHT)).apply { topMargin = dpi(8) })
            showKeys(pages[0].keys)
        } else showKeys(lang.graphemes)
        wordPart.addView(pickerKeys, lpTop(if (pages.size > 1) 4 else 8))

        main.addView(ScrollView(ctx).apply { addView(body) }, lp(MATCH, 0, 1f))
        main.addView(bar, lp(MATCH, WRAP))
        showMain()
    }

    private fun showPage(v: View) {
        removeAllViews()
        addView(v, LayoutParams(MATCH, MATCH))
    }

    private fun showMain() {
        showPage(main)
        refresh()
    }

    private fun editBanner(initial: Banner, onSave: (Banner) -> Unit) =
        showPage(BannerEditorView(context, initial, allowImport = false) { b ->
            if (b != null) onSave(b)
            showMain()
        })

    private fun paste() {
        val found = context.clipboardTexts().firstNotNullOfOrNull { BannerWriter.findAny(it) }?.lines?.flatten()?.filterNotNull()
        if (found.isNullOrEmpty()) { context.toast("No banners found in clipboard"); return }
        banners.addAll(found)
        refresh()
    }

    private fun refresh() {
        val ctx = context
        if (selected >= banners.size) selected = -1
        headingHolder.removeAllViews()
        headingHolder.addView(when {
            word != null -> ctx.heading("Word", "Add one or more banners")
            asWord -> ctx.heading("New word", "Add one or more banners")
            else -> ctx.heading("Insert banners", "Create or paste banners to type them")
        })
        wordPart.visibility = if (asWord) View.VISIBLE else View.GONE
        bannerFlow.removeAllViews()
        banners.forEachIndexed { i, b ->
            bannerFlow.addView(BannerStripView(ctx, listOf(b), ctx.dp(64f)).apply {
                highlighted = i == selected
                setOnClickListener { selected = if (selected == i) -1 else i; refresh() }
            })
        }
        bannerFlow.addView(BannerStripView(ctx, emptyList(), ctx.dp(64f)).apply {
            contentDescription = "Draw a banner"
            setOnClickListener { editBanner(Banner.BLANK) { this@WordEditorView.banners.add(it) } }
        })

        bannerActions.removeAllViews()
        if (selected >= 0) {
            val i = selected
            fun action(icon: Int, desc: String, flip: Boolean = false, f: () -> Unit) =
                bannerActions.addView(ctx.iconButton(icon, desc) { f(); refresh() }.apply { if (flip) scaleX = -1f },
                    ctx.lpIcon().apply { rightMargin = ctx.dpi(6) })
            action(R.drawable.ic_edit, "Edit banner") { editBanner(banners[i]) { banners[i] = it } }
            action(R.drawable.ic_copy, "Duplicate") { banners.add(i + 1, banners[i]); selected = i + 1 }
            action(R.drawable.ic_arrow, "Move earlier", flip = !lang.rtl) {
                if (i > 0) { banners.add(i - 1, banners.removeAt(i)); selected = i - 1 }
            }
            action(R.drawable.ic_arrow, "Move later", flip = lang.rtl) {
                if (i < banners.size - 1) { banners.add(i + 1, banners.removeAt(i)); selected = i + 1 }
            }
            action(R.drawable.ic_delete, "Remove banner") { banners.removeAt(i); selected = -1 }
        }

        spellingList.removeAllViews()
        if (current >= 0) cursor = cursor.coerceIn(0, spellings[current].size)
        spellings.forEachIndexed { i, s ->
            val field = SpellingFieldView(ctx, s.mapNotNull { lang.grapheme(it)?.banner }).apply {
                rtl = lang.rtl
                cursor = if (i == current) this@WordEditorView.cursor else -1
                contentDescription = "Spelling ${i + 1}"
                onTap = { k -> current = i; this@WordEditorView.cursor = k; refresh() }
            }
            spellingList.addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(HorizontalScrollView(ctx).apply {
                    isFillViewport = true
                    isHorizontalScrollBarEnabled = false
                    addView(field)
                }, lp(0, WRAP, 1f))
                addView(ctx.iconButton(R.drawable.ic_backspace, "Delete banner before the cursor") {
                    if (current != i) { current = i; cursor = s.size }
                    if (cursor > 0) s.removeAt(--cursor)
                    refresh()
                }, lp(ctx.dpi(48), ctx.dpi(44)).apply { leftMargin = ctx.dpi(4) })
                addView(ctx.iconButton(R.drawable.ic_close, "Remove spelling") {
                    spellings.removeAt(i)
                    if (current > i || current == spellings.size) current--
                    cursor = spellings.getOrNull(current)?.size ?: 0
                    refresh()
                }, ctx.lpIcon().apply { leftMargin = ctx.dpi(4) })
            }, ctx.lpTop(6))
        }
        refreshBar()
    }

    private fun refreshBar() {
        val ctx = context
        bar.removeAllViews()
        if (word != null) {
            if (confirmDelete) bar.addView(ctx.textButton("Delete word", accent = true) {
                lang.words.remove(word); Repo.save(); onDone(true)
            })
            else bar.addView(ctx.iconButton(R.drawable.ic_delete, "Delete word") {
                confirmDelete = true; refreshBar()
            }, ctx.lpIcon())
        }
        bar.addView(View(ctx), lp(0, 0, 1f))
        bar.addView(ctx.textButton("Cancel") { onDone(false) })
        if (!asWord) bar.addView(ctx.textButton("Insert", accent = true) {
            if (banners.isEmpty()) ctx.toast("Add at least one banner")
            else onInsert?.invoke(banners.toList())
        }, lp(WRAP, WRAP).apply { leftMargin = ctx.dpi(8) })
        else bar.addView(ctx.textButton("Save", accent = true) {
            if (banners.isEmpty()) ctx.toast("Add at least one banner")
            else {
                val clean = spellings.filter { it.isNotEmpty() }.map { it.toList() }.distinct()
                if (word == null) lang.words.add(Word(newId(), banners.toList(), clean))
                else { word.banners = banners.toList(); word.spellings = clean }
                Repo.save(); onDone(true)
            }
        }, lp(WRAP, WRAP).apply { leftMargin = ctx.dpi(8) })
    }
}

class SpellingFieldView(ctx: Context, private val banners: List<Banner>) : View(ctx) {
    var cursor = -1
        set(v) { field = v; invalidate() }
    var rtl = false
        set(v) { field = v; invalidate() }
    var onTap: (Int) -> Unit = {}

    private val bh = dp(40f)
    private val bw = bh / 2f
    private val gap = bh / 16f
    private val padX = dp(10f)
    private val padY = dp(8f)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var blinkOn = true
    private val blink = object : Runnable {
        override fun run() { blinkOn = !blinkOn; invalidate(); postDelayed(this, 530) }
    }

    private val contentWidth get() = padX * 2 + banners.size * bw + (banners.size - 1).coerceAtLeast(0) * gap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = contentWidth.toInt() + 1
        val spec = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) w else maxOf(w, spec),
            (bh + padY * 2).toInt() + 1)
    }

    private fun screenX(x: Float) = if (rtl) width - x else x
    private fun cursorX(k: Int) = if (k == 0) padX - gap else padX + k * (bw + gap) - gap / 2f

    override fun onDraw(canvas: Canvas) {
        p.style = Paint.Style.FILL; p.color = Ui.SURFACE
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        banners.forEachIndexed { i, b ->
            val x = padX + i * (bw + gap)
            val l = if (rtl) width - x - bw else x
            BannerRenderer.draw(canvas, b, RectF(l, padY, l + bw, padY + bh))
        }
        val active = cursor >= 0
        p.color = if (active) Ui.ACCENT else Ui.TEXT_DIM
        val line = dp(if (active) 2f else 1f)
        canvas.drawRect(0f, height - line, width.toFloat(), height.toFloat(), p)
        if (active && blinkOn) {
            val cx = screenX(cursorX(cursor))
            val cw = dp(2f)
            canvas.drawRect(cx - cw / 2f, padY - dp(2f), cx + cw / 2f, padY + bh + dp(2f), p)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (cursor >= 0) {
            postDelayed(blink, 530)
            post {
                val cx = screenX(cursorX(cursor)).toInt()
                requestRectangleOnScreen(android.graphics.Rect(cx - dpi(24), 0, cx + dpi(24), height))
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    private var downX = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> downX = e.x
            MotionEvent.ACTION_UP -> if (Math.abs(e.x - downX) < dp(10f)) {
                val x = screenX(e.x) - padX + gap / 2f
                onTap(Math.round(x / (bw + gap)).coerceIn(0, banners.size))
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
