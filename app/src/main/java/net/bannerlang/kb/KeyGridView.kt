package net.bannerlang.kb

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

class KeyGridView(ctx: Context, private val onKey: (Grapheme) -> Unit) : View(ctx) {
    var keys: List<Grapheme> = emptyList()
        set(v) { field = v; computeGrid(); invalidate() }
    var emptyText = ""
    var rtl = false
        set(v) { field = v; invalidate() }
    var onEmptyTap: () -> Unit = {}
    var onReorder: ((from: Int, to: Int) -> Unit)? = null
    var asKeyboardPreview = false
        set(v) { field = v; requestLayout() }
    var reservedHeight = 0
        set(v) { field = v; requestLayout() }

    private var gridW = 0
    private var gridH = 0
    private val scale get() = if (gridW > 0) width.toFloat() / gridW else 1f

    private var cols = 1
    private var rows = 1
    private val pressed = SparseIntArray()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT_DIM; textSize = dp(15f) }
    private val spacing = dp(4f)
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var dragFrom = -1
    private var dragTo = -1
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private val startDrag = Runnable {
        dragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    init { setBackgroundColor(Ui.BG) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!asKeyboardPreview || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            gridW = 0; gridH = 0
            return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
        val w = MeasureSpec.getSize(widthMeasureSpec)
        gridW = keyboardGridWidth(); gridH = context.keyGridHeight() - reservedHeight
        setMeasuredDimension(w, w * gridH / gridW)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = computeGrid()

    private val layoutW get() = if (gridW > 0) gridW else width
    private val layoutH get() = if (gridW > 0) gridH else height

    private fun computeGrid() {
        val n = keys.size
        if (n == 0 || layoutW == 0 || layoutH == 0) return
        var best = 0f
        for (c in 1..n) {
            val r = (n + c - 1) / c
            val cellW = layoutW.toFloat() / c - spacing * 3
            val cellH = layoutH.toFloat() / r - spacing * 3
            val s = minOf(cellW, cellH / 2f)
            if (s > best) { best = s; cols = c; rows = r }
        }
    }

    private fun cellRect(i: Int): RectF {
        val cw = layoutW.toFloat() / cols
        val ch = layoutH.toFloat() / rows
        val lastRowCount = keys.size - (rows - 1) * cols
        val row = i / cols
        val offset = if (row == rows - 1) (cols - lastRowCount) * cw / 2f else 0f
        val x = offset + (i % cols) * cw
        val y = row * ch
        val r = RectF(x + spacing / 2, y + spacing / 2, x + cw - spacing / 2, y + ch - spacing / 2)
        return if (rtl) RectF(layoutW - r.right, r.top, layoutW - r.left, r.bottom) else r
    }

    private fun keyAt(x: Float, y: Float): Int {
        for (i in keys.indices) if (cellRect(i).contains(x, y)) return i
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(scale, scale)
        drawGrid(canvas)
        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas) {
        if (keys.isEmpty()) {
            val w = (layoutW - dp(32f)).toInt().coerceAtLeast(1)
            val sl = StaticLayout.Builder.obtain(emptyText, 0, emptyText.length, textPaint, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            canvas.save()
            canvas.translate(dp(16f), (layoutH - sl.height) / 2f)
            sl.draw(canvas)
            canvas.restore()
            return
        }
        if (dragging) return drawDrag(canvas)
        val isPressed = BooleanArray(keys.size)
        for (p in 0 until pressed.size()) pressed.valueAt(p).takeIf { it >= 0 }?.let { isPressed[it] = true }
        if (dragFrom >= 0) isPressed[dragFrom] = true
        keys.forEachIndexed { i, g ->
            val r = cellRect(i)
            keyPaint.color = if (isPressed[i]) Ui.KEY_PRESSED else Ui.KEY
            canvas.drawRoundRect(r, dp(6f), dp(6f), keyPaint)
            BannerRenderer.drawFit(canvas, g.banner, RectF(r).apply { inset(spacing, spacing) })
        }
    }

    private fun drawDrag(canvas: Canvas) {
        val order = keys.indices.toMutableList().apply { add(dragTo, removeAt(dragFrom)) }
        order.forEachIndexed { slot, k ->
            val r = cellRect(slot)
            if (k == dragFrom) {
                keyPaint.color = Ui.SURFACE
                canvas.drawRoundRect(r, dp(6f), dp(6f), keyPaint)
                return@forEachIndexed
            }
            keyPaint.color = Ui.KEY
            canvas.drawRoundRect(r, dp(6f), dp(6f), keyPaint)
            BannerRenderer.drawFit(canvas, keys[k].banner, RectF(r).apply { inset(spacing, spacing) })
        }
        val home = cellRect(dragFrom)
        val r = RectF(home).apply { offsetTo(dragX - home.width() / 2, dragY - home.height() / 2) }
        keyPaint.color = Ui.KEY_PRESSED
        canvas.drawRoundRect(r, dp(6f), dp(6f), keyPaint)
        BannerRenderer.drawFit(canvas, keys[dragFrom].banner, RectF(r).apply { inset(spacing, spacing) })
    }

    private fun reorderTouch(e: MotionEvent): Boolean {
        val ex = e.x / scale
        val ey = e.y / scale
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragFrom = keyAt(ex, ey)
                if (dragFrom < 0) return false
                dragTo = dragFrom
                downX = ex; downY = ey; dragX = ex; dragY = ey
                postDelayed(startDrag, 250)
            }
            MotionEvent.ACTION_MOVE -> {
                dragX = ex; dragY = ey
                if (!dragging) {
                    if (Math.hypot((ex - downX).toDouble(), (ey - downY).toDouble()) * scale > touchSlop) endDrag()
                } else {
                    val k = keyAt(ex, ey)
                    if (k >= 0 && k != dragTo) { dragTo = k; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                }
            }
            MotionEvent.ACTION_UP -> {
                val from = dragFrom
                val to = dragTo
                val wasDragging = dragging
                endDrag()
                if (from >= 0) {
                    if (wasDragging) { if (to != from) onReorder?.invoke(from, to) }
                    else onKey(keys[from])
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> endDrag()
        }
        invalidate()
        return true
    }

    private fun endDrag() {
        removeCallbacks(startDrag)
        dragging = false
        dragFrom = -1
        dragTo = -1
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (keys.isEmpty()) {
            if (e.actionMasked == MotionEvent.ACTION_UP) { onEmptyTap(); performClick() }
            return true
        }
        if (onReorder != null) return reorderTouch(e)
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val k = keyAt(e.getX(idx) / scale, e.getY(idx) / scale)
                pressed.put(e.getPointerId(idx), k)
                if (k >= 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(idx)
                val k = pressed.get(id, -1)
                pressed.delete(id)
                if (k >= 0 && k < keys.size) onKey(keys[k])
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> pressed.clear()
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

private fun Context.screenSize(): Point {
    if (Build.VERSION.SDK_INT >= 30) {
        val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return Point(b.width(), b.height())
    }
    val dm = resources.displayMetrics
    return Point(dm.widthPixels, dm.heightPixels)
}

fun Context.keyGridHeight(): Int {
    val h = screenSize().y
    val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    return if (landscape) (h * 0.22f).toInt() else maxOf(dpi(170), (h * 0.3f).toInt())
}

fun View.keyboardGridWidth(): Int {
    var sides = 0
    val insets = rootWindowInsets
    if (insets != null && Build.VERSION.SDK_INT >= 30) {
        val i = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
        sides = i.left + i.right
    } else if (insets != null) {
        @Suppress("DEPRECATION")
        sides = insets.systemWindowInsetLeft + insets.systemWindowInsetRight
    }
    return context.screenSize().x - sides - dpi(KEY_GRID_MARGIN) * 2
}

const val KEY_GRID_MARGIN = 2

class BannerKeyView(ctx: Context) : View(ctx) {
    var banner: Banner? = null
        set(v) { field = v; invalidate() }

    init { background = Ui.bg(Ui.KEY, dp(8f)) }

    override fun onDraw(canvas: Canvas) {
        val b = banner ?: return
        BannerRenderer.drawFit(canvas, b, RectF(0f, 0f, width.toFloat(), height.toFloat()).apply { inset(dp(6f), dp(5f)) })
    }
}

const val GROUP_TABS_HEIGHT = 38

class GroupTabsView(ctx: Context, private val onPick: (Int) -> Unit) : HorizontalScrollView(ctx) {
    private val row = LinearLayout(ctx)
    private val barPaint = Paint().apply { color = Ui.TEXT_DIM }

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Ui.BG)
        addView(row, FrameLayout.LayoutParams(WRAP, MATCH))
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val range = row.width
        if (range <= width) return
        val w = width.toFloat() * width / range
        val l = scrollX + (width - w) * scrollX / (range - width)
        canvas.drawRect(l, height - dp(2f), l + w, height.toFloat(), barPaint)
    }

    fun set(icons: List<Banner>, selected: Int, rtl: Boolean) {
        layoutDirection = if (rtl) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
        row.removeAllViews()
        icons.forEachIndexed { i, b ->
            row.addView(BannerKeyView(context).apply {
                banner = b
                background = Ui.bg(if (i == selected) Ui.KEY_PRESSED else Ui.SURFACE, dp(8f))
                contentDescription = "Group ${i + 1}"
                setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onPick(i) }
            }, lp(dpi(28), MATCH).apply { setMargins(dpi(2), dpi(3), dpi(2), dpi(3)) })
        }
    }
}
