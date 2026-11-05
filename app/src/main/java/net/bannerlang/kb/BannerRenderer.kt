package net.bannerlang.kb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream

// this renderer was mostly built by AI but it works well enough
object BannerRenderer {
    private val cache = LruCache<String, Bitmap>(512)
    private val pixelPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val outlinePaint = Paint().apply { style = Paint.Style.STROKE; color = 0x66000000 }
    private val illegalPaint = Paint().apply { style = Paint.Style.STROKE; color = 0xFFE53935.toInt() }
    private val src = Rect(0, 0, Patterns.W, Patterns.H)

    private var generation = -1

    fun bitmap(banner: Banner): Bitmap {
        if (generation != Textures.generation) {
            generation = Textures.generation
            cache.evictAll()
        }
        val key = banner.encode()
        cache.get(key)?.let { return it }
        val cloth = Textures.cloth()
        val px = if (cloth != null) composeTextures(banner, cloth) else composeMasks(banner)
        val bmp = Bitmap.createBitmap(px, Patterns.W, Patterns.H, Bitmap.Config.ARGB_8888)
        cache.put(key, bmp)
        return bmp
    }

    private fun composeTextures(banner: Banner, cloth: IntArray): IntArray {
        val px = if (banner.clearBase) IntArray(cloth.size) else IntArray(cloth.size) { cloth[it] or OPAQUE }
        val layers = (if (banner.clearBase) emptyList() else listOf(Layer("base", banner.base))) +
            banner.layers.take(Patterns.MAX_LAYERS)
        for (layer in layers) {
            val tex = Textures.face(layer.pattern) ?: continue
            val c = DyeColors.RGB[layer.color]
            val cr = ((c shr 16) and 0xFF) / 255f
            val cg = ((c shr 8) and 0xFF) / 255f
            val cb = (c and 0xFF) / 255f
            for (i in px.indices) {
                val t = tex[i]
                val a = (t ushr 24) / 255f
                if (a <= 0f) continue
                val d = px[i]
                val da = (d ushr 24) / 255f * (1f - a)
                val oa = a + da
                fun ch(shift: Int, tint: Float): Int {
                    val s = ((t shr shift) and 0xFF) * tint
                    val v = (s * a + ((d shr shift) and 0xFF) * da) / oa
                    return (v + .5f).toInt().coerceIn(0, 255) shl shift
                }
                px[i] = ((oa * 255 + .5f).toInt() shl 24) or ch(16, cr) or ch(8, cg) or ch(0, cb)
            }
        }
        return px
    }

    private fun composeMasks(banner: Banner): IntArray {
        val px = IntArray(Patterns.W * Patterns.H) { if (banner.clearBase) 0 else DyeColors.RGB[banner.base] or OPAQUE }
        for (layer in banner.layers.take(Patterns.MAX_LAYERS)) {
            val mask = Patterns.get(layer.pattern)?.mask ?: continue
            val c = DyeColors.RGB[layer.color] or OPAQUE
            for (i in px.indices) {
                val a = mask[i]
                if (a <= 0f) continue
                px[i] = if (a >= 1f) c else blend(px[i], c, a)
            }
        }
        return px
    }

    private fun blend(dst: Int, src: Int, a: Float): Int {
        val da = (dst ushr 24) / 255f * (1f - a)
        val oa = a + da
        fun ch(shift: Int): Int {
            val d = (dst shr shift) and 0xFF
            val s = (src shr shift) and 0xFF
            return ((s * a + d * da) / oa + .5f).toInt() shl shift
        }
        return ((oa * 255 + .5f).toInt() shl 24) or ch(16) or ch(8) or ch(0)
    }

    private const val OPAQUE = 0xFF000000.toInt()

    fun illegal(b: Banner) = b.layers.size > Patterns.LOOM_LAYERS || b.layers.any { it.pattern == "base" }

    fun draw(canvas: Canvas, banner: Banner, dst: RectF, outline: Boolean = true) {
        canvas.drawBitmap(bitmap(banner), src, dst, pixelPaint)
        if (!outline) return
        val w = (dst.width() / 40f).coerceAtLeast(1f)
        if (Repo.illegalOutline && illegal(banner)) {
            illegalPaint.strokeWidth = w * 1.5f
            canvas.drawRect(RectF(dst).apply { inset(w * .75f, w * .75f) }, illegalPaint)
        } else {
            outlinePaint.strokeWidth = w
            canvas.drawRect(dst, outlinePaint)
        }
    }

    fun drawFit(canvas: Canvas, banner: Banner, box: RectF, outline: Boolean = true) {
        val h = minOf(box.height(), box.width() * 2f)
        val w = h / 2f
        val l = box.centerX() - w / 2f
        val t = box.centerY() - h / 2f
        draw(canvas, banner, RectF(l, t, l + w, t + h), outline)
    }

    val EXPORT_SCALES = intArrayOf(1, 2, 4, 8)
    private const val EXPORT_BG = 0xFF313338.toInt()

    val CHAT_WIDTHS = intArrayOf(0, 8, 12, 16, 24)

    fun exportPng(lines: List<List<Banner?>>, out: File, rtl: Boolean = false, s: Int = 1, minCols: Int = 0) {
        val cw = Patterns.W * s
        val ch = Patterns.H * s
        val gap = s * 2
        val pad = s * 2
        val maxCols = maxOf(lines.maxOfOrNull { it.size } ?: 1, minCols, 1)
        val width = pad * 2 + maxCols * cw + (maxCols - 1) * gap
        val height = pad * 2 + lines.size * ch + (lines.size - 1).coerceAtLeast(0) * gap
        val bmp = Bitmap.createBitmap(width, height.coerceAtLeast(pad * 2 + ch), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(EXPORT_BG)
        lines.forEachIndexed { li, line ->
            val top = pad + li * (ch + gap).toFloat()
            line.forEachIndexed { i, b ->
                if (b != null) {
                    val left = if (rtl) width - pad - cw - i * (cw + gap).toFloat()
                               else pad + i * (cw + gap).toFloat()
                    draw(c, b, RectF(left, top, left + cw, top + ch), outline = false)
                }
            }
        }
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    fun contrastOn(color: Int): Int {
        val rgb = DyeColors.RGB[color]
        val l = 0.299 * Color.red(rgb) + 0.587 * Color.green(rgb) + 0.114 * Color.blue(rgb)
        return if (l > 140) Color.BLACK else Color.WHITE
    }
}
