package io.github.redartem39.resonance.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** A place the library is scanned from: our own download folder (a plain file) or a folder the user picked (SAF tree). */
sealed interface Root {
    data class Dir(val file: File) : Root
    data class Tree(val uri: Uri) : Root
}

object Library {
    // ExoPlayer cannot decode WMA, so it is left out (the desktop app lists it, but it could not be played here anyway).
    val EXT = setOf("mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "webm", "mp4", "m4b", "oga", "mka")
    private val COVER_NAMES = listOf("cover", "folder", "front", "album")
    private val COVER_EXTS = listOf("jpg", "jpeg", "png", "webp")
    private val YT_NAME = Regex("^([\\w-]{11})\\.\\w+$")

    fun downloadDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: ctx.filesDir, "Resonance").also { it.mkdirs() }

    private class Cand(val path: String, val uri: Uri, val name: String, val size: Long, val mtime: Long, val cover: String?, val isFile: Boolean)

    // ------------------------------------------------------------------ walking
    private fun extOf(name: String) = name.substringAfterLast('.', "").lowercase()
    private fun coverIn(names: List<Pair<String, String>>): String? { // (display name, uri) pairs of one directory
        for (n in COVER_NAMES) for (x in COVER_EXTS) names.firstOrNull { it.first.equals("$n.$x", true) }?.let { return it.second }
        return null
    }

    private fun walk(ctx: Context, root: Root, out: MutableList<Cand>) {
        when (root) {
            is Root.Dir -> walkDir(root.file, out)
            is Root.Tree -> {
                val id = DocumentsContract.getTreeDocumentId(root.uri)
                walkTree(ctx, root.uri, id, out)
            }
        }
    }

    private fun walkDir(dir: File, out: MutableList<Cand>) {
        val entries = dir.listFiles() ?: return
        val cover = coverIn(entries.filter { it.isFile }.map { it.name to Uri.fromFile(it).toString() })
        for (f in entries.sortedBy { it.name }) {
            if (f.isDirectory) walkDir(f, out)
            else if (extOf(f.name) in EXT) {
                // downloaded files bring their own thumbnail (<id>.jpg / .webp / .png)
                val own = COVER_EXTS.map { File(f.parentFile, f.nameWithoutExtension + "." + it) }.firstOrNull { it.exists() }
                out += Cand(f.absolutePath, Uri.fromFile(f), f.name, f.length(), f.lastModified(), own?.let { Uri.fromFile(it).toString() } ?: cover, true)
            }
        }
    }

    private fun walkTree(ctx: Context, tree: Uri, docId: String, out: MutableList<Cand>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        class E(val id: String, val name: String, val mime: String, val size: Long, val mtime: Long)
        val list = ArrayList<E>()
        try {
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) list += E(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), c.getLong(4))
            }
        } catch (_: Exception) { return }
        val docUri = { id: String -> DocumentsContract.buildDocumentUriUsingTree(tree, id) }
        val cover = coverIn(list.filter { it.mime != DocumentsContract.Document.MIME_TYPE_DIR }.map { it.name to docUri(it.id).toString() })
        for (e in list.sortedBy { it.name }) {
            if (e.mime == DocumentsContract.Document.MIME_TYPE_DIR) walkTree(ctx, tree, e.id, out)
            else if (extOf(e.name) in EXT) {
                val u = docUri(e.id)
                out += Cand(u.toString(), u, e.name, e.size, e.mtime, cover, false)
            }
        }
    }

    // ------------------------------------------------------------------ metadata (cached by uri + size + mtime)
    private class Meta(val title: String?, val artist: String?, val album: String?, val albumArtist: String?, val no: Int, val disk: Int, val year: Int, val dur: Double)
    private var cache: MutableMap<String, Meta>? = null
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "meta-cache.json")
    private fun loadCache(ctx: Context): MutableMap<String, Meta> = cache ?: synchronized(this) {
        cache ?: HashMap<String, Meta>().also { m ->
            runCatching {
                val o = JSONObject(cacheFile(ctx).readText())
                for (k in o.keys()) {
                    val a = o.getJSONArray(k)
                    fun s(i: Int) = a.optString(i, "").ifEmpty { null }
                    m[k] = Meta(s(0), s(1), s(2), s(3), a.optInt(4), a.optInt(5), a.optInt(6), a.optDouble(7, 0.0))
                }
            }
            cache = m
        }
    }
    private fun saveCache(ctx: Context) {
        val m = cache ?: return
        runCatching {
            val o = JSONObject()
            synchronized(m) { for ((k, v) in m) o.put(k, org.json.JSONArray(listOf(v.title ?: "", v.artist ?: "", v.album ?: "", v.albumArtist ?: "", v.no, v.disk, v.year, v.dur))) }
            cacheFile(ctx).writeText(o.toString())
        }
    }

    private fun num(s: String?): Int = s?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0

    private fun readMeta(ctx: Context, c: Cand): Meta {
        val mmr = MediaMetadataRetriever()
        return try {
            if (c.isFile) mmr.setDataSource(c.path) else mmr.setDataSource(ctx, c.uri)
            fun g(k: Int) = mmr.extractMetadata(k)?.trim()?.ifEmpty { null }
            Meta(
                g(MediaMetadataRetriever.METADATA_KEY_TITLE), g(MediaMetadataRetriever.METADATA_KEY_ARTIST), g(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                g(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                num(g(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)), num(g(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                (g(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: "").take(4).toIntOrNull() ?: 0,
                (g(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0,
            )
        } catch (_: Exception) {
            Meta(null, null, null, null, 0, 0, 0, 0.0)
        } finally { runCatching { mmr.release() } }
    }

    private fun sidecar(c: Cand): JSONObject? {
        if (!c.isFile) return null
        val f = File(c.path.substringBeforeLast('.') + ".meta.json")
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    private fun build(ctx: Context, c: Cand, overrides: JSONObject): Track {
        val m = loadCache(ctx).let { cc ->
            val k = "${c.path}|${c.size}|${c.mtime}"
            synchronized(cc) { cc[k] } ?: readMeta(ctx, c).also { synchronized(cc) { cc[k] = it } }
        }
        val base = c.name.substringBeforeLast('.')
        var title = m.title ?: base
        var artist = m.artist ?: UNKNOWN_ARTIST
        var album = m.album ?: NO_ALBUM
        var albumArtist = m.albumArtist ?: m.artist ?: UNKNOWN_ARTIST
        sidecar(c)?.let { side ->
            val (a, t) = if (side.has("ytTitle")) splitMeta(side.optString("ytTitle"), side.optString("ytAuthor")) else side.optString("artist") to side.optString("title")
            if (t.isNotEmpty()) title = t
            if (a.isNotEmpty()) { artist = a; albumArtist = a }
            album = side.optString("album").ifEmpty { if (album == NO_ALBUM) YT_ALBUM else album }
        }
        var albumKey: String? = null
        overrides.optJSONObject(c.path)?.let { o ->
            o.optString("title").takeIf { it.isNotEmpty() }?.let { title = it }
            o.optString("artist").takeIf { it.isNotEmpty() }?.let { artist = it }
            o.optString("album").takeIf { it.isNotEmpty() }?.let { album = it }
            o.optString("albumKey").takeIf { it.isNotEmpty() }?.let { albumKey = it }
        }
        return Track(
            path = c.path, uri = c.uri, title = title, artist = artist, album = album, albumArtist = albumArtist,
            no = m.no, disk = m.disk, year = m.year, duration = m.dur, albumKey = albumKey, coverUri = c.cover,
            ytId = YT_NAME.matchEntire(c.name)?.groupValues?.get(1)?.takeIf { c.isFile }, isFile = c.isFile,
        )
    }

    /** Scans all roots; [onBatch] gets tracks in groups of 24 as they are read, like the desktop scan. Returns the total. */
    suspend fun scan(ctx: Context, roots: List<Root>, onBatch: suspend (List<Track>) -> Unit): Int = withContext(Dispatchers.IO) {
        val cands = ArrayList<Cand>()
        for (r in roots) walk(ctx, r, cands)
        val seen = HashSet<String>()
        val unique = cands.filter { seen.add(it.path) }
        val ov = loadOverrides(ctx)
        var total = 0
        val gate = Semaphore(4)
        for (chunk in unique.chunked(24)) {
            val tracks = coroutineScope { chunk.map { c -> async { gate.withPermit { build(ctx, c, ov) } } }.awaitAll() }
            total += tracks.size
            onBatch(tracks)
        }
        saveCache(ctx)
        total
    }

    // ------------------------------------------------------------------ user edits (rename / move to album), kept in the profile
    private var overridesCache: JSONObject? = null
    private fun overridesFile(ctx: Context) = File(ctx.filesDir, "overrides.json")
    @Synchronized private fun loadOverrides(ctx: Context): JSONObject =
        overridesCache ?: (runCatching { JSONObject(overridesFile(ctx).readText()) }.getOrDefault(JSONObject())).also { overridesCache = it }

    @Synchronized fun updateTrack(ctx: Context, path: String, patch: Map<String, String>) {
        val ov = loadOverrides(ctx)
        val cur = ov.optJSONObject(path) ?: JSONObject()
        for ((k, v) in patch) if (k in setOf("title", "artist", "album", "albumKey")) cur.put(k, v.take(300))
        ov.put(path, cur)
        overridesFile(ctx).writeText(ov.toString())
    }

    /** Deletes a track. Android has no Recycle Bin for app-owned access, so the UI warns that this cannot be undone. */
    @Synchronized fun deleteTrack(ctx: Context, t: Track): Result<Unit> = runCatching {
        if (t.isFile) {
            val f = File(t.path)
            val base = f.path.substringBeforeLast('.')
            val isDownload = File("$base.meta.json").exists()
            if (f.exists() && !f.delete()) error("Не удалось удалить файл")
            if (isDownload) for (s in listOf(".meta.json", ".webp", ".jpg", ".jpeg", ".png")) File(base + s).delete()
        } else {
            if (!DocumentsContract.deleteDocument(ctx.contentResolver, t.uri)) error("Папка не даёт удалить этот файл")
        }
        val ov = loadOverrides(ctx)
        if (ov.has(t.path)) { ov.remove(t.path); overridesFile(ctx).writeText(ov.toString()) }
    }
}
