package net.bannerlang.kb

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

class BannerEditorView(
    ctx: Context,
    initial: Banner,
    allowImport: Boolean = true,
    title: String? = null,
    private val onDone: (Banner?) -> Unit
) : LinearLayout(ctx) {

    private var base = initial.base
    private var clearBase = initial.clearBase
    private val layers = initial.layers.toMutableList()
    private var sel = -1
    private var color = if (initial.base == DyeColors.BLACK) DyeColors.WHITE else DyeColors.BLACK

    private val preview = object : View(ctx) {
        override fun onDraw(canvas: Canvas) {
            BannerRenderer.drawFit(canvas, shown(), RectF(0f, 0f, width.toFloat(), height.toFloat()))
        }
    }
    private val layerList = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val layerScroll = ScrollView(ctx).apply { addView(layerList) }
    private val hint = ctx.label("", 13f)
    private val palette = PaletteView(ctx) { c -> onColor(c) }
    private val patterns = PatternGridView(ctx) { id -> onPattern(id) }

    private var dragFrom = -1
    private var dragTo = -1
    private var dragRow: View? = null
    private var dragStartY = 0f
    private var dragRawY = 0f
    private var scrollTop = 0
    private val autoScroll = object : Runnable {
        override fun run() {
            if (dragRow == null) return
            val edge = dpi(28)
            val y = dragRawY - scrollTop
            val dy = when {
                y < edge -> -dpi(6)
                y > layerScroll.height - edge -> dpi(6)
                else -> 0
            }
            if (dy != 0 && layerScroll.canScrollVertically(dy)) { layerScroll.scrollBy(0, dy); dragMove() }
            postDelayed(this, 16)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.BG)
        val pad = dpi(12)
        setPadding(pad, pad, pad, pad)

        if (title != null) addView(ctx.label(title, 18f, Ui.TEXT), lp(MATCH, WRAP).apply { bottomMargin = dpi(8) })
        val top = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            addView(preview, lp(dpi(72), MATCH))
            addView(layerScroll, lp(0, MATCH, 1f).apply { leftMargin = pad })
        }
        addView(top, lp(MATCH, dpi(150)))
        addView(hint, lpTop(8))
        addView(palette, lpTop(4))
        addView(ScrollView(ctx).apply { addView(patterns) }, lp(MATCH, 0, 1f).apply { topMargin = dpi(8) })

        val bar = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (allowImport) addView(ctx.textButton("Import") {
                ImportDialog.show(ctx) { banners -> load(banners.first()) }
            })
            addView(View(ctx), lp(0, 0, 1f))
            addView(ctx.textButton("Cancel") { onDone(null) }, lp(WRAP, WRAP))
            addView(ctx.textButton("Save", accent = true) { onDone(banner()) }, lp(WRAP, WRAP).apply { leftMargin = pad })
        }
        addView(bar, lpTop(8))
        refresh()
    }

    fun banner() = Banner(base, layers.toList(), clearBase)

    private fun shown() =
        if (dragFrom < 0) banner() else Banner(base, layers.toMutableList().apply { add(dragTo, removeAt(dragFrom)) }, clearBase)

    private fun load(b: Banner) {
        base = b.base
        clearBase = b.clearBase
        layers.clear(); layers.addAll(b.layers)
        sel = -1
        refresh()
    }

    private fun onColor(c: Int) {
        color = c
        when {
            sel == 0 -> base = c
            sel > 0 -> layers[sel - 1] = layers[sel - 1].copy(color = c)
        }
        refresh()
    }

    private fun onPattern(id: String) {
        if (sel > 0) {
            layers[sel - 1] = layers[sel - 1].copy(pattern = id)
        } else {
            layers.add(Layer(id, color))
            sel = -1
        }
        refresh()
    }

    private fun select(i: Int) {
        sel = if (sel == i) -1 else i
        if (sel == 0) color = base
        if (sel > 0) color = layers[sel - 1].color
        refresh()
    }

    private fun refresh() {
        preview.invalidate()
        palette.selected = color
        patterns.base = base
        patterns.color = color
        patterns.selectedId = if (sel > 0) layers[sel - 1].pattern else null
        hint.text = when {
            sel == 0 -> "Base selected: pick a color"
            sel > 0 -> "Layer $sel selected: change its color or pattern"
            else -> "Pick a color, then tap a pattern to add it" +
                when {
                    layers.size > Patterns.MAX_LAYERS -> " · the game only shows ${Patterns.MAX_LAYERS} layers"
                    layers.size > 6 -> " · a loom allows 6 layers"
                    else -> ""
                }
        }
        rebuildLayers()
    }

    private fun rebuildLayers() {
        layerList.removeAllViews()
        for (i in layers.indices.reversed()) {
            val l = layers[i]
            layerList.addView(layerRow(i + 1, Banner(DyeColors.backdrop(base, l.color), listOf(l)), l.color))
        }
        layerList.addView(layerRow(0, Banner(base, clearBase = clearBase), base))
    }

    private fun paint(index: Int) {
        if (index == 0) base = color else layers[index - 1] = layers[index - 1].copy(color = color)
        refresh()
    }

    private fun layerRow(index: Int, thumb: Banner, swatch: Int): View {
        val ctx = context
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.bg(if (sel == index) Ui.KEY_PRESSED else Ui.SURFACE, dp(8f))
            setPadding(dpi(4), dpi(2), dpi(4), dpi(2))
            setOnClickListener { select(index) }
        }
        row.addView(BannerStripView(ctx, listOf(thumb), dp(32f)))
        row.addView(SwatchView(ctx, swatch).apply {
            setPadding(dpi(4), dpi(4), dpi(4), dpi(4))
            setOnClickListener { if (swatch != color) paint(index) else select(index) }
        }, lp(dpi(30), dpi(30)))
        if (index == 0) {
            row.addView(ctx.label("Base", 13f, Ui.TEXT), lp(0, WRAP, 1f))
            row.addView(ctx.iconButton(if (clearBase) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                if (clearBase) "Show base" else "Hide base", Ui.SURFACE) { clearBase = !clearBase; refresh() },
                lp(dpi(34), dpi(34)))
        } else {
            row.addView(View(ctx), lp(0, 0, 1f))
            val i = index - 1
            fun small(icon: Int, desc: String, f: () -> Unit) =
                row.addView(ctx.iconButton(icon, desc, Ui.SURFACE) { f(); refresh() }, lp(dpi(34), dpi(34)))
            small(R.drawable.ic_copy, "Duplicate") { layers.add(i + 1, layers[i]); sel = -1 }
            small(R.drawable.ic_delete, "Delete") { layers.removeAt(i); sel = -1 }
            row.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_drag)
                contentDescription = "Drag to move"
                imageTintList = ColorStateList.valueOf(Ui.TEXT_DIM)
                scaleType = ImageView.ScaleType.CENTER
                setOnTouchListener { v, e -> dragTouch(v, row, i, e); true }
            }, lp(dpi(40), dpi(34)))
        }
        return row.also { it.layoutParams = lp(MATCH, WRAP).apply { bottomMargin = dpi(3) } }
    }

    private fun listY(rawY: Float) = rawY - scrollTop + layerScroll.scrollY

    private fun dragTouch(handle: View, row: View, i: Int, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handle.parent.requestDisallowInterceptTouchEvent(true)
                scrollTop = IntArray(2).also { layerScroll.getLocationOnScreen(it) }[1]
                dragRow = row; dragFrom = i; dragTo = i
                dragRawY = e.rawY; dragStartY = listY(e.rawY)
                row.translationZ = dp(6f)
                row.background = Ui.bg(Ui.KEY_PRESSED, dp(8f))
                post(autoScroll)
            }
            MotionEvent.ACTION_MOVE -> { dragRawY = e.rawY; dragMove() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragEnd()
        }
    }

    private fun dragMove() {
        val row = dragRow ?: return
        val n = layers.size
        val from = n - 1 - dragFrom
        val step = row.height + dpi(3).toFloat()
        val ty = (listY(dragRawY) - dragStartY).coerceIn(-from * step, (n - 1 - from) * step)
        row.translationY = ty
        val to = (from + Math.round(ty / step)).coerceIn(0, n - 1)
        for (p in 0 until n) {
            if (p == from) continue
            layerList.getChildAt(p).translationY = when {
                p in (from + 1)..to -> -step
                p in to until from -> step
                else -> 0f
            }
        }
        if (n - 1 - to != dragTo) {
            dragTo = n - 1 - to
            row.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) // small vibration
            preview.invalidate()
        }
    }

    private fun dragEnd() {
        if (dragRow == null) return
        removeCallbacks(autoScroll)
        val from = dragFrom
        val to = dragTo
        dragRow = null; dragFrom = -1; dragTo = -1
        if (from != to) {
            layers.add(to, layers.removeAt(from))
            val s = sel - 1
            if (s >= 0) sel = 1 + when {
                s == from -> to
                s in (from + 1)..to -> s - 1
                s in to until from -> s + 1
                else -> s
            }
        }
        // rebuild after drag event is done because the handle has moved
        post { refresh() }
    }

    companion object {
        fun showDialog(ctx: Context, initial: Banner, title: String? = null, onSave: (Banner) -> Unit) {
            val d = Dialog(ctx)
            d.requestWindowFeature(Window.FEATURE_NO_TITLE)
            d.setContentView(BannerEditorView(ctx, initial, title = title) { b ->
                d.dismiss()
                if (b != null) onSave(b)
            })
            d.showLarge()
        }
    }
}

class SwatchView(ctx: Context, private val color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        p.style = Paint.Style.FILL; p.color = DyeColors.argb(color)
        val r = RectF(paddingLeft + 1f, paddingTop + 1f, width - paddingRight - 1f, height - paddingBottom - 1f)
        canvas.drawRoundRect(r, dp(4f), dp(4f), p)
        p.style = Paint.Style.STROKE; p.color = Ui.TEXT_DIM; p.strokeWidth = dp(1f)
        canvas.drawRoundRect(r, dp(4f), dp(4f), p)
    }
}

class PaletteView(ctx: Context, private val onPick: (Int) -> Unit) : View(ctx) {
    var selected = 0
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cols get() = if (width / 16 >= dpi(30)) 16 else 8

    init { contentDescription = "Dye colors" }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val c = if (w / 16 >= dpi(30)) 16 else 8
        val cell = w / c
        setMeasuredDimension(w, minOf(cell, dpi(44)) * (16 / c))
    }

    private fun cellRect(i: Int): RectF {
        val c = cols
        val cw = width.toFloat() / c
        val ch = height.toFloat() / (16 / c)
        val x = (i % c) * cw
        val y = (i / c) * ch
        return RectF(x, y, x + cw, y + ch)
    }

    override fun onDraw(canvas: Canvas) {
        val inset = dp(3f)
        for (i in 0 until 16) {
            val r = cellRect(i).apply { inset(inset, inset) }
            p.style = Paint.Style.FILL; p.color = DyeColors.argb(i)
            canvas.drawRoundRect(r, dp(5f), dp(5f), p)
            p.style = Paint.Style.STROKE
            if (i == selected) {
                p.color = Ui.ACCENT; p.strokeWidth = dp(3f)
                canvas.drawRoundRect(RectF(r).apply { inset(-inset / 1.5f, -inset / 1.5f) }, dp(6f), dp(6f), p)
            } else {
                p.color = 0x55FFFFFF; p.strokeWidth = dp(1f)
                canvas.drawRoundRect(r, dp(5f), dp(5f), p)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            for (i in 0 until 16) if (cellRect(i).contains(e.x, e.y)) { onPick(i); performClick(); break }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

class PatternGridView(ctx: Context, private val onPick: (String) -> Unit) : View(ctx) {
    var base = 0
        set(v) { field = v; invalidate() }
    var color = 15
        set(v) { field = v; invalidate() }
    var selectedId: String? = null
        set(v) { field = v; invalidate() }

    private val minCell = dpi(42)
    private val inset = dp(5f)
    private fun rowHeight(cw: Float) = (cw - inset * 2) * 2 + inset * 2
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cols get() = (width / minCell).coerceAtLeast(4)
    private var list = Patterns.list()
    private val n get() = list.size

    init { contentDescription = "Patterns" }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val c = (w / minCell).coerceAtLeast(4)
        val cw = w / c
        setMeasuredDimension(w, Math.ceil(((n + c - 1) / c) * rowHeight(cw.toFloat()).toDouble()).toInt())
    }

    private fun cellRect(i: Int): RectF {
        val cw = width.toFloat() / cols
        val x = (i % cols) * cw
        val rh = rowHeight(cw)
        val y = (i / cols) * rh
        return RectF(x, y, x + cw, y + rh)
    }

    override fun onDraw(canvas: Canvas) {
        val shownBase = DyeColors.backdrop(base, color)
        list.forEachIndexed { i, pat ->
            val r = cellRect(i)
            val inner = RectF(r).apply { inset(inset, inset) }
            BannerRenderer.drawFit(canvas, Banner(shownBase, listOf(Layer(pat.id, color))), inner)
            if (pat.id == selectedId) {
                p.color = Ui.ACCENT; p.strokeWidth = dp(3f)
                canvas.drawRoundRect(RectF(r).apply { inset(dp(2f), dp(2f)) }, dp(4f), dp(4f), p)
            }
        }
    }

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (Math.abs(e.x - downX) < dp(10f) && Math.abs(e.y - downY) < dp(10f)) {
                    for (i in 0 until n) if (cellRect(i).contains(e.x, e.y)) {
                        onPick(list[i].id); performClick(); break
                    }
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
