package io.github.redartem39.resonance.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Cover art: the picture embedded in the file, else the downloaded thumbnail / folder art. Cached per album (per file for downloads). */
object Covers {
    private val cache = object : LruCache<String, Any>(48) {}
    private val NONE = Any()

    fun cacheKey(t: Track): String = if (t.ytId != null) t.path else t.key()

    private fun decode(bytes: ByteArray, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= max && o.outHeight / (s * 2) >= max) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun loadBytes(ctx: Context, t: Track): ByteArray? = loadBytes(ctx, t.isFile, t.path, t.uri, t.coverUri)

    fun loadBytes(ctx: Context, isFile: Boolean, path: String, uri: Uri, coverUri: String?): ByteArray? {
        val mmr = MediaMetadataRetriever()
        try {
            if (isFile) mmr.setDataSource(path) else mmr.setDataSource(ctx, uri)
            mmr.embeddedPicture?.let { return it }
        } catch (_: Exception) {
        } finally { runCatching { mmr.release() } }
        if (coverUri != null) runCatching {
            val u = Uri.parse(coverUri)
            return if (u.scheme == "file") File(u.path!!).readBytes() else ctx.contentResolver.openInputStream(u)?.use { it.readBytes() }
        }
        return null
    }

    /** Bitmap no larger than ~[max] px on a side, or null. */
    suspend fun load(ctx: Context, t: Track, max: Int = 700): Bitmap? {
        val k = cacheKey(t) + "@" + max
        cache.get(k)?.let { return it as? Bitmap }
        val bmp = withContext(Dispatchers.IO) { loadBytes(ctx, t)?.let { runCatching { decode(it, max) }.getOrNull() } }
        cache.put(k, bmp ?: NONE)
        return bmp
    }
}
