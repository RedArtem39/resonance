package io.github.redartem39.resonance.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class LyricLine(val t: Double, val text: String)
/** [synced]/[plain] are the raw LRCLIB strings; [scale] stretches timestamps (sped-up / slowed versions). */
class LyricsData(val synced: String?, val plain: String?, val scale: Double = 1.0)
class LyricCandidate(val artist: String, val title: String, val album: String, val duration: Double, val synced: String?, val plain: String?)
class LyricsQuery(val artist: String, val title: String, val album: String, val duration: Double)

/** LRCLIB lookups, cached on disk like the desktop app. */
object Lyrics {
    private const val UA = "Resonance/0.5 (offline music player)"
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    private fun dir(ctx: Context) = File(ctx.filesDir, "lyrics").apply { mkdirs() }
    private fun file(ctx: Context, q: LyricsQuery): File {
        val h = MessageDigest.getInstance("SHA-1").digest("${q.artist}|${q.title}|${Math.round(q.duration)}".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(ctx), "$h.json")
    }

    private fun get(url: String): String? {
        val r = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute()
        r.use { return if (it.isSuccessful) it.body?.string() else null }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Free-text search for the manual picker. Throws with a readable message on failure. */
    suspend fun search(text: String): List<LyricCandidate> = withContext(Dispatchers.IO) {
        val r = http.newCall(Request.Builder().url("https://lrclib.net/api/search?q=${enc(text.take(200))}").header("User-Agent", UA).build()).execute()
        r.use {
            if (!it.isSuccessful) error(if (it.code == 503) "Сервер LRCLIB перегружен, попробуй ещё раз через минуту" else "LRCLIB ответил ${it.code}")
            val a = JSONArray(it.body!!.string())
            val out = ArrayList<LyricCandidate>()
            for (i in 0 until a.length()) {
                val x = a.getJSONObject(i)
                val syn = x.optString("syncedLyrics").takeIf { s -> s.isNotEmpty() && s != "null" }
                val pl = x.optString("plainLyrics").takeIf { s -> s.isNotEmpty() && s != "null" }
                if (syn == null && pl == null) continue
                out += LyricCandidate(x.optString("artistName"), x.optString("trackName"), x.optString("albumName"), x.optDouble("duration", 0.0), syn, pl)
                if (out.size >= 15) break
            }
            out
        }
    }

    /** Remember the user's choice for this exact track. */
    suspend fun save(ctx: Context, q: LyricsQuery, synced: String?, plain: String?, scale: Double) = withContext(Dispatchers.IO) {
        val sc = if (scale.isFinite() && scale > 0.2 && scale < 5) scale else 1.0
        file(ctx, q).writeText(JSONObject().put("synced", synced ?: JSONObject.NULL).put("plain", plain ?: JSONObject.NULL).put("scale", sc).toString())
    }

    private fun parse(o: JSONObject) = LyricsData(
        o.optString("synced").takeIf { it.isNotEmpty() && it != "null" },
        o.optString("plain").takeIf { it.isNotEmpty() && it != "null" },
        o.optDouble("scale", 1.0).takeIf { it.isFinite() } ?: 1.0,
    )

    suspend fun load(ctx: Context, q: LyricsQuery): LyricsData? = withContext(Dispatchers.IO) {
        val f = file(ctx, q)
        runCatching { return@withContext parse(JSONObject(f.readText())) }
        try {
            val known = q.artist.isNotEmpty() && !q.artist.startsWith("Неизвестный")
            var hit: JSONObject? = null
            if (known) {
                var url = "https://lrclib.net/api/get?artist_name=${enc(q.artist)}&track_name=${enc(q.title)}&duration=${Math.round(q.duration)}"
                if (q.album.isNotEmpty() && q.album != NO_ALBUM && q.album != YT_ALBUM) url += "&album_name=${enc(q.album)}"
                hit = get(url)?.let { JSONObject(it) }
            }
            if (hit == null) {
                val list = get("https://lrclib.net/api/search?q=${enc(if (known) "${q.artist} ${q.title}" else q.title)}")?.let { JSONArray(it) }
                val cands = ArrayList<JSONObject>()
                if (list != null) for (i in 0 until list.length()) {
                    val x = list.getJSONObject(i)
                    if (x.optString("syncedLyrics").let { it.isNotEmpty() && it != "null" } || x.optString("plainLyrics").let { it.isNotEmpty() && it != "null" }) cands += x
                }
                hit = cands.sortedWith(
                    compareByDescending<JSONObject> { it.optString("syncedLyrics").let { s -> s.isNotEmpty() && s != "null" } }
                        .thenBy { Math.abs(it.optDouble("duration", 0.0) - q.duration) },
                ).firstOrNull()
            }
            if (hit == null) return@withContext null
            val syn = hit.optString("syncedLyrics").takeIf { it.isNotEmpty() && it != "null" }
            val pl = hit.optString("plainLyrics").takeIf { it.isNotEmpty() && it != "null" }
            f.writeText(JSONObject().put("synced", syn ?: JSONObject.NULL).put("plain", pl ?: JSONObject.NULL).toString())
            LyricsData(syn, pl)
        } catch (_: Exception) { null }
    }

    private val stamp = Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)]")
    private val wordStamp = Regex("<\\d+:\\d+(?:\\.\\d+)?>")

    fun parseLrc(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in text.split(Regex("\\r?\\n"))) {
            val ms = stamp.findAll(raw).toList()
            if (ms.isEmpty()) continue
            val txt = raw.substring(ms.last().range.last + 1).replace(wordStamp, "").trim()
            for (m in ms) out += LyricLine(m.groupValues[1].toDouble() * 60 + m.groupValues[2].toDouble(), txt)
        }
        return out.sortedBy { it.t }
    }

    fun parsePlain(text: String): List<LyricLine> {
        val lines = text.split(Regex("\\r?\\n")).map { it.trim() }
        return lines.filterIndexed { i, l -> l.isNotEmpty() || (i > 0 && lines[i - 1].isNotEmpty()) }.map { LyricLine(0.0, it) }
    }
}
