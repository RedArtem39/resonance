package io.github.redartem39.resonance.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.security.MessageDigest

/** Serves a track's cover art as a JPEG to the media notification and lock screen. */
class CoverProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val src = uri.getQueryParameter("u") ?: return null
        val isFile = uri.getQueryParameter("f") == "1"
        val dir = File(ctx.cacheDir, "covers").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-1").digest(src.toByteArray()).joinToString("") { "%02x".format(it) }
        val out = File(dir, "$name.jpg")
        if (!out.exists()) {
            val bytes = Covers.loadBytes(ctx, isFile, if (isFile) src else "", Uri.parse(src), uri.getQueryParameter("c")) ?: return null
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (o.outWidth / (s * 2) >= 512) s *= 2
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        }
        return ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String = "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        fun uriFor(t: Track): Uri = Uri.Builder().scheme("content").authority("io.github.redartem39.resonance.covers").path("/c")
            .appendQueryParameter("u", if (t.isFile) t.path else t.uri.toString())
            .appendQueryParameter("f", if (t.isFile) "1" else "0")
            .apply { t.coverUri?.let { appendQueryParameter("c", it) } }.build()
    }
}
