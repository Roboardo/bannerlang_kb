package net.bannerlang.kb

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView

object Ui {
    const val BG = 0xFF202124.toInt()
    const val SURFACE = 0xFF2D2E31.toInt()
    const val KEY = 0xFF3C4043.toInt()
    const val KEY_PRESSED = 0xFF5F6368.toInt()
    const val ACCENT = 0xFF8AB4F8.toInt()
    const val ON_ACCENT = 0xFF202124.toInt()
    const val TEXT = 0xFFE8EAED.toInt()
    const val TEXT_DIM = 0xFF9AA0A6.toInt()

    fun bg(color: Int, radius: Float, pressed: Int = 0x33FFFFFF): Drawable {
        val shape = GradientDrawable().apply { setColor(color); cornerRadius = radius }
        val mask = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = radius }
        return RippleDrawable(ColorStateList.valueOf(pressed), shape, mask)
    }
}

fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun Context.dpi(v: Int): Int = dp(v.toFloat()).toInt()
fun View.dp(v: Float) = context.dp(v)
fun View.dpi(v: Int) = context.dpi(v)

fun Context.iconButton(icon: Int, desc: String, color: Int = Ui.KEY, onClick: (View) -> Unit): ImageButton =
    ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = desc
        background = Ui.bg(color, dp(8f))
        imageTintList = ColorStateList.valueOf(if (color == Ui.ACCENT) Ui.ON_ACCENT else Ui.TEXT)
        setPadding(dpi(6), dpi(6), dpi(6), dpi(6))
        setOnClickListener(onClick)
    }

fun Context.textButton(text: String, accent: Boolean = false, onClick: (View) -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(if (accent) Ui.ON_ACCENT else Ui.TEXT)
        background = Ui.bg(if (accent) Ui.ACCENT else Ui.KEY, dp(8f))
        minHeight = dpi(44); minimumHeight = dpi(44)
        setPadding(dpi(14), 0, dpi(14), 0)
        stateListAnimator = null
        setOnClickListener(onClick)
    }

fun Context.label(text: String, sizeSp: Float = 14f, color: Int = Ui.TEXT_DIM): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = sizeSp
        gravity = Gravity.CENTER_VERTICAL
    }

fun Context.screenTitle(text: String) = label(text, 24f, Ui.TEXT)

fun Context.heading(title: String, sub: String? = null) = android.widget.LinearLayout(this).apply {
    orientation = android.widget.LinearLayout.VERTICAL
    addView(label(title, 18f, Ui.TEXT))
    if (sub != null) addView(label(sub), lpTop(2))
}

fun Dialog.showLarge() {
    val dm = context.resources.displayMetrics
    window?.setLayout((dm.widthPixels * 0.96f).toInt(), (dm.heightPixels * 0.9f).toInt())
    show()
}

fun lp(w: Int, h: Int, weight: Float = 0f, margin: Int = 0) =
    android.widget.LinearLayout.LayoutParams(w, h, weight).apply { setMargins(margin, margin, margin, margin) }

fun Context.column() = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }

fun Context.lpTop(top: Int) = lp(MATCH, WRAP).apply { topMargin = dpi(top) }
fun View.lpTop(top: Int) = context.lpTop(top)
fun Context.lpIcon() = lp(dpi(44), dpi(44))
fun View.lpIcon() = context.lpIcon()

fun Context.switch(on: Boolean, text: String? = null, onChange: (Boolean) -> Unit) = android.widget.Switch(this).apply {
    this.text = text
    setTextColor(Ui.TEXT)
    isChecked = on
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    thumbTintList = ColorStateList(states, intArrayOf(Ui.ACCENT, Ui.TEXT_DIM))
    trackTintList = ColorStateList(states, intArrayOf(Ui.ACCENT, Ui.KEY))
    setOnCheckedChangeListener { _, v -> onChange(v) }
}

fun Activity.screen(): android.widget.LinearLayout {
    val content = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dpi(16), dpi(16), dpi(16), dpi(16))
    }
    setContentView(android.widget.ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(content); padForSystemBars(top = true) })
    return content
}

fun Context.clipboardTexts(): List<String> {
    val clip = getSystemService(android.content.ClipboardManager::class.java)?.primaryClip ?: return emptyList()
    val texts = mutableListOf<String>()
    for (i in 0 until clip.itemCount) {
        val item = clip.getItemAt(i)
        item.text?.let { texts.add(it.toString()) }
        item.htmlText?.let { texts.add(it); texts.add(android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString()) }
        item.uri?.let { texts.add(it.toString()) }
        try { texts.add(item.coerceToText(this).toString()) } catch (_: Exception) {}
    }
    if (clip.itemCount > 1) texts.add(texts.joinToString(" "))
    return texts
}

fun View.padForSystemBars(top: Boolean) {
    val l = paddingLeft; val t = paddingTop; val r = paddingRight; val b = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        val left: Int; val topI: Int; val right: Int; val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val types = WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout() or
                if (top) WindowInsets.Type.statusBars() else 0
            val i = insets.getInsets(types)
            left = i.left; topI = i.top; right = i.right; bottom = i.bottom
        } else {
            @Suppress("DEPRECATION")
            left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            topI = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            right = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            bottom = insets.systemWindowInsetBottom
        }
        v.setPadding(l + left, t + if (top) topI else 0, r + right, b + bottom)
        insets
    }
    requestApplyInsets()
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class BannerStripView(ctx: Context, banners: List<Banner> = emptyList(), private val bannerHeight: Float = ctx.dp(48f)) :
    View(ctx) {
    var banners: List<Banner> = banners
        set(v) { field = v; requestLayout(); invalidate() }
    var highlighted = false
        set(v) { field = v; invalidate() }
    var rtl = false
        set(v) { field = v; invalidate() }

    private val gap = bannerHeight / 16f
    private val pad = dp(4f)
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Ui.TEXT_DIM; strokeWidth = dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
    }
    private val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.ACCENT; strokeWidth = dp(2.5f) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = banners.size.coerceAtLeast(1)
        val w = pad * 2 + n * bannerHeight / 2f + (n - 1) * gap
        setMeasuredDimension(w.toInt() + 1, (bannerHeight + pad * 2).toInt() + 1)
    }

    override fun onDraw(canvas: Canvas) {
        val bw = bannerHeight / 2f
        if (banners.isEmpty()) {
            val r = RectF(pad, pad, pad + bw, pad + bannerHeight)
            canvas.drawRect(r, dash)
            canvas.drawLine(r.centerX() - bw / 4, r.centerY(), r.centerX() + bw / 4, r.centerY(), dash)
            canvas.drawLine(r.centerX(), r.centerY() - bw / 4, r.centerX(), r.centerY() + bw / 4, dash)
        }
        banners.forEachIndexed { i, b ->
            val slot = if (rtl) banners.size - 1 - i else i
            val l = pad + slot * (bw + gap)
            BannerRenderer.draw(canvas, b, RectF(l, pad, l + bw, pad + bannerHeight))
        }
        if (highlighted) canvas.drawRoundRect(RectF(1f, 1f, width - 1f, height - 1f), pad, pad, hl)
    }
}

class FlowLayout(ctx: Context, private val spacing: Int = ctx.dpi(6)) : ViewGroup(ctx) {
    var rtl = false
        set(v) { field = v; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + spacing; rowH = 0 }
            x += c.measuredWidth + spacing
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + spacing; rowH = 0 }
            val left = if (rtl) paddingLeft + maxW - x - c.measuredWidth else paddingLeft + x
            c.layout(left, paddingTop + y, left + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + spacing
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}
