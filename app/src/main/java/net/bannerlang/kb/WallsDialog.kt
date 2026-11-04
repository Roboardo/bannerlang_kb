package net.bannerlang.kb

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.DateFormat
import java.util.Date

object WallsDialog {
    fun show(ctx: Context, currentId: String?, onNew: () -> Unit, onOpen: (Doc) -> Unit, onDeleted: (Doc) -> Unit = {}) {
        val list = Docs.list(ctx)
        lateinit var dialog: AlertDialog
        val title = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(24), ctx.dpi(16), ctx.dpi(16), 0)
            addView(ctx.label("Walls", 20f, Ui.TEXT), lp(0, WRAP, 1f))
            addView(ctx.iconButton(R.drawable.ic_add, "New wall", Ui.ACCENT) { dialog.dismiss(); onNew() },
                ctx.lpIcon())
        }
        val box = ctx.column().apply {
            setPadding(ctx.dpi(16), ctx.dpi(8), ctx.dpi(16), ctx.dpi(8))
            if (list.isEmpty()) addView(ctx.label("No saved walls yet."), ctx.lpTop(4))
            val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            for (d in list) addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.bg(if (d.id == currentId) Ui.KEY else Ui.SURFACE, ctx.dp(8f))
                setPadding(ctx.dpi(12), ctx.dpi(6), ctx.dpi(6), ctx.dpi(6))
                setOnClickListener { dialog.dismiss(); onOpen(d) }
                addView(ctx.column().apply {
                    addView(BannerStripView(ctx, d.title, ctx.dp(32f)).apply { rtl = d.rtl })
                    addView(ctx.label(fmt.format(Date(d.modified)) + " · ${d.columns} wide", 13f))
                }, lp(0, WRAP, 1f))
                addView(ctx.iconButton(R.drawable.ic_delete, "Delete wall") {
                    AlertDialog.Builder(ctx)
                        .setMessage("Delete this wall?")
                        .setPositiveButton("Delete") { _, _ ->
                            Docs.delete(ctx, d.id)
                            onDeleted(d)
                            dialog.dismiss(); show(ctx, currentId, onNew, onOpen, onDeleted)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }, ctx.lpIcon())
            }, ctx.lpTop(8))
        }
        dialog = AlertDialog.Builder(ctx).setCustomTitle(title).setView(ScrollView(ctx).apply { addView(box) }).create()
        dialog.show()
    }
}
