package net.bannerlang.kb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

// tool for image export
class ImageProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "net.bannerlang.kb.images"
        const val DIR = "out"
        fun uriFor(f: File): Uri = Uri.parse("content://$AUTHORITY/${f.name}")
    }

    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment ?: ""
        if (!name.matches(Regex("[A-Za-z0-9_-]+\\.png"))) throw FileNotFoundException(name)
        val f = File(File(context!!.cacheDir, DIR), name)
        if (!f.exists()) throw FileNotFoundException(name)
        return f
    }

    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        return MatrixCursor(cols).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
