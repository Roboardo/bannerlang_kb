package net.bannerlang.kb

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import java.io.File

class KeyboardService : InputMethodService(), KeyboardPanel.Host {
    private lateinit var panel: KeyboardPanel
    private val msg get() = panel.msg
    private val lang get() = panel.lang
    private var sendModeDialog: AlertDialog? = null

    override val isIme = true

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Textures.init(this)
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        panel = KeyboardPanel(this, this).apply { padForSystemBars(top = false) }
        return panel
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        sendModeDialog?.dismiss()
        if (!::panel.isInitialized) return
        panel.closeEditor()
        panel.reloadLanguage()
        Backup.check()
    }

    override fun sendHold() = showSendModeDialog()

    private fun showSendModeDialog() {
        val d = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Send as")
            .setSingleChoiceItems(Output.entries.map { it.label }.toTypedArray(), Repo.output.ordinal) { d, which ->
                Repo.output = Output.entries[which]
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .create()
        // keyboards have no activity so dialog attaches to the keyboard's own window
        d.window?.apply {
            attributes = attributes.apply {
                token = panel.windowToken
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            // taking focus from the app's text field would close the keyboard.
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        d.setOnDismissListener { sendModeDialog = null }
        sendModeDialog = d
        d.show()
    }

    override fun send() {
        panel.commitComposing()
        if (msg.isEmpty) {
            panel.refresh()
            sendDefaultEditorAction(true)
            return
        }
        if (Repo.output != Output.IMAGE) {
            val w = BannerWriter.Writing(lang?.rtl == true, msg.lines())
            val text = BannerWriter.imageLink(w)
            currentInputConnection?.commitText(text, 1)
            msg.clear()
            panel.refresh()
            return
        }
        val dir = File(cacheDir, ImageProvider.DIR).apply { mkdirs() }
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(10)?.forEach { it.delete() }
        val file = File(dir, "banners_${System.currentTimeMillis()}.png")
        BannerRenderer.exportPng(msg.lines(), file, lang?.rtl == true, Repo.exportScale, Repo.chatWidth)
        val uri = ImageProvider.uriFor(file)

        val accepts = currentInputEditorInfo?.contentMimeTypes
            ?.any { ClipDescription.compareMimeTypes("image/png", it) } == true
        val committed = accepts && currentInputConnection?.commitContent(
            InputContentInfo(uri, ClipDescription("Banner text", arrayOf("image/png"))),
            InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null
        ) == true
        if (!committed) shareFallback(uri)
        msg.clear()
        panel.refresh()
    }

    private fun shareFallback(uri: Uri) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newUri(contentResolver, "Banner text", uri))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Send banner text").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        toast("Can't submit image here. Copied to clipboard.")
    }

    override fun switchKeyboard() {
        if (!switchToNextInputMethod(false)) imePicker()
    }

    override fun imePicker() = getSystemService(InputMethodManager::class.java).showInputMethodPicker()

    override fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
