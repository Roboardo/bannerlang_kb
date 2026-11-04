package net.bannerlang.kb

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Textures.init(this)
    }
}

class MainActivity : Activity(), Backup.Listener {
    private lateinit var content: LinearLayout
    private val received = mutableListOf<Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = screen()
    }

    private val onTexturesChanged: () -> Unit = { rebuild() }

    override fun onResume() {
        super.onResume()
        Textures.addListener(onTexturesChanged)
        rebuild()
    }

    override fun onSynced() = rebuild()

    override fun onPause() {
        super.onPause()
        Textures.removeListener(onTexturesChanged)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) rebuild()
    }

    private fun rebuild() {
        content.removeAllViews()
        content.addView(screenTitle("Banner Keyboard"))

        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        val hasLanguage = Repo.languages.any { it.graphemes.isNotEmpty() }
        if (!enabled || !hasLanguage) addChecklist(enabled, hasLanguage)
        else content.addView(label(if (current) "Keyboard is on and selected." else "Keyboard is on. Switch to it to type."),
            lpTop(8))
        content.addView(LinearLayout(this).apply {
            if (enabled) addView(textButton("Switch keyboard", accent = !current) {
                imm.showInputMethodPicker()
            }, lp(WRAP, WRAP).apply { rightMargin = dpi(8) })
            addView(textButton("Settings") {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            })
        }, lpTop(8))

        if (enabled && hasLanguage) addTextureStatus()

        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(heading("Languages", if (Repo.languages.isEmpty())
                "No languages yet. Tap the empty slot to add one, or import a file."
            else "Tap to edit. Selected language is highlighted."), lp(0, WRAP, 1f))
            addView(textButton("Import") { importLanguage() })
        }, lpTop(24))

        val flow = FlowLayout(this, dpi(10))
        val sel = Repo.selected()
        for (l in Repo.languages) {
            flow.addView(BannerStripView(this, listOf(l.icon), dp(80f)).apply {
                highlighted = l === sel
                contentDescription = "Language"
                setOnClickListener { openLanguage(l) }
            })
        }
        flow.addView(BannerStripView(this, emptyList(), dp(80f)).apply {
            contentDescription = "New language"
            setOnClickListener {
                BannerEditorView.showDialog(this@MainActivity, Banner.BLANK, "Language icon") { icon ->
                    val l = Language(newId(), icon)
                    Repo.languages.add(l)
                    Repo.select(l.id)
                    openLanguage(l)
                }
            }
        })
        content.addView(flow, lpTop(8))

        content.addView(heading("Wall", "Write persistent banner text on a configurable wall."),
            lpTop(24))
        fun wall() = Intent(this, WriteActivity::class.java)
        val newWall = { startActivity(wall().putExtra(WriteActivity.START, WriteActivity.START_NEW)) }
        content.addView(FlowLayout(this, dpi(8)).apply {
            addView(textButton("View wall", accent = true) { startActivity(wall()) })
            addView(textButton("New wall") { newWall() })
            addView(textButton("Saved walls") {
                WallsDialog.show(this@MainActivity, null, onNew = newWall,
                    onOpen = { d -> startActivity(wall().putExtra(WriteActivity.OPEN, d.id)) })
            })
        }, lpTop(8))

        content.addView(heading("Test the keyboard"), lpTop(24))
        val images = column()
        fun show(bmp: Bitmap) = images.addView(ImageView(this).apply {
            setImageDrawable(BitmapDrawable(resources, bmp).apply { paint.isFilterBitmap = false })
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
        }, 0, lp(WRAP, WRAP).apply { topMargin = dpi(8) })
        content.addView(ImageEditText(this) { bmp ->
            bmp.density = DisplayMetrics.DENSITY_DEFAULT * Repo.exportScale
            received.add(bmp)
            show(bmp)
            if (received.size > 10) { received.removeAt(0); images.removeViewAt(images.childCount - 1) }
        }.apply {
            hint = "Type here"
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_DIM)
        }, lp(MATCH, WRAP))
        received.forEach { show(it) }
        content.addView(images, lp(MATCH, WRAP))
    }

    private fun addChecklist(enabled: Boolean, hasLanguage: Boolean) {
        content.addView(heading("Get started"), lpTop(16))
        fun step(n: Int, title: String, done: Boolean, sub: String, vararg buttons: View) {
            content.addView(label(if (done) "✓ $title" else "$n. $title", 16f, if (done) Ui.ACCENT else Ui.TEXT),
                lpTop(12))
            if (done) return
            content.addView(label(sub), lpTop(2))
            if (buttons.isNotEmpty()) content.addView(FlowLayout(this, dpi(8)).apply { buttons.forEach { addView(it) } },
                lpTop(8))
        }
        step(1, "Turn on the keyboard", enabled,
            "Android will warn that keyboards can collect what you type. This app has no internet access, " +
                "so nothing you type leaves your phone.",
            textButton("Enable keyboard", accent = true) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
        step(2, "Add or import a language", hasLanguage,
            if (Repo.languages.isEmpty()) "Make one under Languages, or import one."
            else "Add banners (the keys) to a language, or import one.",
            textButton("Import", accent = enabled) { importLanguage() })
    }

    private fun importLanguage() {
        AlertDialog.Builder(this)
            .setTitle("Import")
            .setItems(arrayOf("Backup file", "From banner-writer (experimental)", "From bannerdb")) { _, which ->
                when (which) {
                    0 -> SettingsActivity.pickFile(this, false, SettingsActivity.IMPORT)
                    1 -> BannerWriterImport.show(this)
                    else -> SettingsActivity.pickFile(this, false, SettingsActivity.BANNERDB)
                }
            }
            .show()
    }

    @Deprecated("Activity result API needs AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            SettingsActivity.IMPORT -> SettingsActivity.importFile(this, uri) { rebuild() }
            SettingsActivity.BANNERDB -> SettingsActivity.importBannerDb(this, uri) { rebuild() }
        }
    }

    private fun addTextureStatus() {
        if (Textures.installed && !Textures.busy) return
        val text = when {
            Textures.busy -> Textures.status ?: "Reading textures…"
            else -> "Using placeholder textures. Add the real ones in Settings."
        }
        content.addView(label(text), lpTop(16))
    }

    private fun openLanguage(l: Language) {
        Repo.select(l.id)
        startActivity(Intent(this, LanguageActivity::class.java).putExtra("id", l.id))
    }
}
