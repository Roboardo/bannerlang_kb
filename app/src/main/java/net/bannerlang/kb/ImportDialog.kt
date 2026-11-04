package net.bannerlang.kb

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.widget.EditText

object ImportDialog {
    private fun parse(s: String): List<Banner>? = BannerWriter.findAny(s)?.lines?.flatten()?.filterNotNull()

    fun show(ctx: Context, onImport: (List<Banner>) -> Unit) {
        val clip = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        val input = EditText(ctx).apply {
            hint = "Banner code, BannerFont text or banner-writer link"
            minLines = 2
            if (clip != null && parse(clip) != null) setText(clip)
        }
        val box = ctx.column().apply {
            setPadding(ctx.dpi(20), ctx.dpi(8), ctx.dpi(20), 0)
            addView(ctx.label("Paste a /getbannercode code, BannerFont text, or a banner-writer link or image link."))
            addView(input)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Import banners")
            .setView(box)
            .setPositiveButton("Import") { _, _ ->
                val banners = parse(input.text.toString())
                if (banners == null) ctx.toast("Couldn't read that. Check it's a banner code, BannerFont text or banner-writer link.")
                else onImport(banners)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
