package net.bannerlang.kb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import android.widget.EditText

class ImageEditText(ctx: Context, private val onImage: (Bitmap) -> Unit) : EditText(ctx) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.contentMimeTypes = arrayOf("image/*")
        return object : InputConnectionWrapper(ic, false) {
            override fun commitContent(info: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
                val grant = flags and InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0
                return try {
                    if (grant) info.requestPermission()
                    val bmp = context.contentResolver.openInputStream(info.contentUri)
                        ?.use { BitmapFactory.decodeStream(it) } ?: return false
                    post { onImage(bmp) }
                    true
                } catch (e: Exception) {
                    false
                } finally {
                    if (grant) info.releasePermission()
                }
            }
        }
    }
}
