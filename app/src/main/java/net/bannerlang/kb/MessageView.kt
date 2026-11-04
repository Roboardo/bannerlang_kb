package net.bannerlang.kb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import kotlin.math.abs

class MessageView(ctx: Context) : View(ctx) {
    var message = Message()
    var composing: List<Banner> = emptyList()
    var onTap: (Int) -> Unit = {}
    var onSelect: (Int, Int) -> Unit = { _, _ -> }
    var onHold: (Float, Float) -> Unit = { _, _ -> }
    var onSelectEnd: () -> Unit = {}
    var rtl = false
    var keepWords = false
        set(v) { field = v; invalidate() }

    var columns = 0
        set(v) { field = v; update() }

    var pageLines = 0
        set(v) { field = v; invalidate() }

    private var bh = dp(40f)
    private var bw = bh / 2f
    private val gap = dp(3f)
    private var lineH = bh + dp(10f)
    private val pad = dp(8f)
    private val nlW = dp(12f)

    private val selPaint = Paint().apply { color = 0x668AB4F8 }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT; strokeWidth = dp(2f) }
    private val guide = Paint().apply { color = 0x14FFFFFF; style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val pageLine = Paint().apply { color = 0x668AB4F8; strokeWidth = dp(1.5f) }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x669AA0A6; strokeWidth = dp(1.5f); style = Paint.Style.STROKE }

    private enum class Kind { BANNER, SPACE, NEWLINE }
    private class Item(val kind: Kind, val banner: Banner?, val composing: Boolean)
    private var items = listOf<Item>()
    private var xs = FloatArray(0)
    private var ls = IntArray(0)
    private var endX = 0f
    private var endLine = 0

    private var scroll = 0f
    private var followCursor = true

    init {
        setBackgroundColor(Ui.SURFACE)
        contentDescription = "Message preview"
    }

    private var shownLines = 1

    fun update() {
        followCursor = true
        if (width > 0) {
            layoutItems(width)
            if (columns == 0 && minOf(endLine + 1, MAX_LINES) != shownLines) requestLayout()
        }
        invalidate()
    }

    private fun heightFor(lines: Int) = pad * 2 + (lines - 1) * lineH + bh

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        layoutItems(w)
        shownLines = minOf(endLine + 1, MAX_LINES)
        val h = if (columns > 0 && MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightSpec)
                else heightFor(shownLines).toInt()
        setMeasuredDimension(w, h)
    }

    private val cursorGap get() = message.cursor + composing.size

    private fun width(it: Item) = if (it.kind == Kind.NEWLINE) nlW else bw

    private fun layoutItems(viewW: Int) {
        val list = ArrayList<Item>(message.tokens.size + composing.size)
        message.tokens.forEachIndexed { i, t ->
            if (i == message.cursor) composing.forEach { list.add(Item(Kind.BANNER, it, true)) }
            list.add(when (t) {
                is Token.B -> Item(Kind.BANNER, t.banner, false)
                Token.Space -> Item(Kind.SPACE, null, false)
                Token.Newline -> Item(Kind.NEWLINE, null, false)
            })
        }
        if (message.cursor == message.tokens.size) composing.forEach { list.add(Item(Kind.BANNER, it, true)) }
        items = list

        if (columns > 0) {
            bw = ((viewW - pad * 2 - (columns - 1) * gap) / columns).coerceAtLeast(dp(4f))
            bh = bw * 2
            lineH = bh + dp(10f)
        }
        val cols = if (columns > 0) columns else ((viewW - pad * 2 + gap) / (bw + gap)).toInt()
        val w = wrap(list.map { when (it.kind) {
            Kind.BANNER -> Token.B(it.banner!!)
            Kind.SPACE -> Token.Space
            Kind.NEWLINE -> Token.Newline
        } }, cols, keepWords)
        fun x(col: Int) = pad + col * (bw + gap)
        ls = w.line
        xs = FloatArray(list.size) { i ->
            if (list[i].kind == Kind.NEWLINE) minOf(x(w.col[i]), viewW - pad / 2 - nlW) else x(w.col[i])
        }
        endX = x(w.endCol); endLine = w.endLine
    }

    private fun vx(x: Float, w: Float = 0f) = if (rtl) width - x - w else x

    private fun gapPos(g: Int): Pair<Float, Int> =
        if (g < items.size) xs[g] to ls[g] else endX to endLine

    private fun lineTop(line: Int) = pad + line * lineH - scroll

    override fun onDraw(canvas: Canvas) {
        layoutItems(width)
        val contentH = heightFor(endLine + 1)
        val maxScroll = (contentH - height).coerceAtLeast(0f)
        if (followCursor) {
            val cl = gapPos(cursorGap).second
            val top = pad + cl * lineH
            if (top - pad < scroll) scroll = top - pad
            if (top + bh + pad > scroll + height) scroll = top + bh + pad - height
            followCursor = false
        }
        scroll = scroll.coerceIn(0f, maxScroll)

        if (columns > 0) {
            val first = (scroll / lineH).toInt().coerceAtLeast(0)
            for (line in first..first + (height / lineH).toInt() + 1) {
                val t = lineTop(line)
                for (c in 0 until columns) {
                    val l = pad + c * (bw + gap)
                    canvas.drawRect(l, t, l + bw, t + bh, guide)
                }
                if (pageLines > 0 && line > 0 && line % pageLines == 0) {
                    val y = t - (lineH - bh) / 2
                    canvas.drawLine(pad, y, width - pad, y, pageLine)
                }
            }
        }
        val sel = message.hasSelection && composing.isEmpty()
        items.forEachIndexed { i, it ->
            val t = lineTop(ls[i])
            if (t + lineH < 0 || t > height) return@forEachIndexed
            val w = width(it)
            val l = vx(xs[i], w)
            if (sel && i >= message.selStart && i < message.selEnd) {
                canvas.drawRect(l - gap / 2, t - dp(3f), l + w + gap / 2, t + bh + dp(3f), selPaint)
            }
            when (it.kind) {
                Kind.BANNER -> {
                    BannerRenderer.draw(canvas, it.banner!!, RectF(l, t, l + bw, t + bh))
                    if (it.composing) canvas.drawLine(l - gap / 2, t + bh + dp(4f), l + bw + gap / 2, t + bh + dp(4f), accent)
                }
                Kind.SPACE -> canvas.drawLine(l + dp(2f), t + bh - dp(1f), l + bw - dp(2f), t + bh - dp(1f), dim)
                Kind.NEWLINE -> {
                    val cx = l + w / 2; val cy = t + bh / 2
                    canvas.save()
                    if (rtl) canvas.scale(-1f, 1f, cx, cy)
                    canvas.drawLine(cx + dp(4f), cy - dp(6f), cx + dp(4f), cy + dp(2f), dim)
                    canvas.drawLine(cx + dp(4f), cy + dp(2f), cx - dp(4f), cy + dp(2f), dim)
                    canvas.drawLine(cx - dp(4f), cy + dp(2f), cx - dp(1f), cy - dp(1f), dim)
                    canvas.drawLine(cx - dp(4f), cy + dp(2f), cx - dp(1f), cy + dp(5f), dim)
                    canvas.restore()
                }
            }
        }

        if (!sel && (SystemClock.uptimeMillis() / 530) % 2 == 0L) {
            val (cx, cl) = gapPos(cursorGap)
            val t = lineTop(cl)
            val x = vx(cx - gap / 2)
            canvas.drawLine(x, t - dp(2f), x, t + bh + dp(2f), accent)
        }
        postInvalidateDelayed(530)
    }

    fun selectionBounds(): RectF? {
        if (!message.hasSelection || composing.isNotEmpty() || width == 0) return null
        layoutItems(width)
        var r: RectF? = null
        for (i in message.selStart until minOf(message.selEnd, items.size)) {
            val t = lineTop(ls[i])
            val w = width(items[i])
            val l = vx(xs[i], w)
            val b = RectF(l, t, l + w, t + bh)
            if (r == null) r = b else r.union(b)
        }
        if (r == null || !r.intersect(0f, 0f, width.toFloat(), height.toFloat())) return null
        return r
    }

    private fun gapAt(rawX: Float, y: Float): Int {
        val x = vx(rawX)
        val line = ((y + scroll - pad) / lineH).toInt().coerceAtLeast(0)
        if (line > endLine) return items.size
        var best = -1
        var bestD = Float.MAX_VALUE
        fun consider(g: Int, gx: Float) {
            val d = abs(gx - x)
            if (d < bestD) { bestD = d; best = g }
        }
        for (i in items.indices) if (ls[i] == line) {
            consider(i, xs[i])
            if (items[i].kind != Kind.NEWLINE) consider(i + 1, xs[i] + width(items[i]))
        }
        if (line == endLine) consider(items.size, endX)
        return if (best >= 0) best else items.size
    }

    private fun itemAt(rawX: Float, y: Float): Int {
        val x = vx(rawX)
        val line = ((y + scroll - pad) / lineH).toInt()
        for (i in items.indices) if (ls[i] == line && x >= xs[i] && x < xs[i] + width(items[i]) + gap) return i
        return -1
    }

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var moved = false
    private var selecting = false
    private var held = false
    private var selAnchorItem = 0
    private val longPress = Runnable {
        val i = itemAt(downX, downY)
        if (moved) return@Runnable
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        if (i >= 0) {
            selecting = true
            selAnchorItem = i
            onSelect(i, i + 1)
        } else {
            held = true
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastY = e.y
                moved = false; selecting = false; held = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (selecting) {
                    val g = gapAt(e.x, e.y)
                    if (g > selAnchorItem) onSelect(selAnchorItem, g) else onSelect(selAnchorItem + 1, g)
                    if (e.y < 0) scroll -= lineH / 4 else if (e.y > height) scroll += lineH / 4
                    invalidate()
                } else {
                    if (!moved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                        moved = true
                        removeCallbacks(longPress)
                    }
                    if (moved) {
                        scroll -= e.y - lastY
                        invalidate()
                    }
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!moved && !selecting && !held) { onTap(gapAt(e.x, e.y)); performClick() }
                if (selecting) onSelectEnd()
                if (held) onHold(downX, downY)
                selecting = false
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); selecting = false }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    companion object {
        const val MAX_LINES = 3
    }
}
