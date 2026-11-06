package io.github.redartem39.resonance.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request as HttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class YtItem(val id: String, val title: String, val author: String, val seconds: Long, val thumb: String)

/** YouTube search + audio download. NewPipeExtractor plays the role of yt-search + yt-dlp from the desktop app. */
object Yt {
    private lateinit var appCtx: Context
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private var ready = false

    private fun cookieFile() = File(appCtx.filesDir, "cookies/youtube.txt")
    private fun cookieHeader(): String? = runCatching { Cookies.header(cookieFile().readText()).takeIf { it.isNotEmpty() } }.getOrNull()
    @Volatile var useCookies = true

    private class Dl : Downloader() {
        override fun execute(request: Request): Response {
            val b = HttpRequest.Builder().url(request.url())
            val body = request.dataToSend()?.toRequestBody()
            b.method(request.httpMethod(), body ?: if (request.httpMethod() == "POST") ByteArray(0).toRequestBody() else null)
            b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0")
            for ((k, vs) in request.headers()) { b.removeHeader(k); for (v in vs) b.addHeader(k, v) }
            if (useCookies && request.url().contains("youtube.com")) cookieHeader()?.let { b.header("Cookie", it) }
            http.newCall(b.build()).execute().use { r ->
                if (r.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                val text = r.body?.string()
                return Response(r.code, r.message, r.headers.toMultimap(), text, r.request.url.toString())
            }
        }
    }

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        if (!ready) { NewPipe.init(Dl(), Localization.fromLocale(java.util.Locale.getDefault())); ready = true }
    }

    // ---------------------------------------------------------------- cookies (optional login of a spare account)
    fun cookieInfo(): Pair<Boolean, Int> = runCatching { val t = cookieFile().readText(); Cookies.count(t).let { (it > 0) to it } }.getOrDefault(false to 0)
    fun importCookies(raw: String): Int {
        val r = Cookies.normalize(raw)
        cookieFile().apply { parentFile?.mkdirs(); writeText(r.text) }
        return r.count
    }
    fun removeCookies() { cookieFile().delete() }

    // ---------------------------------------------------------------- search
    private fun idOf(url: String): String? = Regex("[?&]v=([\\w-]{11})").find(url)?.groupValues?.get(1) ?: Regex("youtu\\.be/([\\w-]{11})").find(url)?.groupValues?.get(1)

    suspend fun search(query: String): List<YtItem> = withContext(Dispatchers.IO) {
        val ex = ServiceList.YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        ex.fetchPage()
        ex.initialPage.items.filterIsInstance<StreamInfoItem>().mapNotNull { v ->
            val id = idOf(v.url) ?: return@mapNotNull null
            YtItem(id, v.name, v.uploaderName ?: "", v.duration, v.thumbnails.maxByOrNull { it.height }?.url ?: v.thumbnails.firstOrNull()?.url ?: "")
        }.take(12)
    }

    // ---------------------------------------------------------------- download
    private const val CHUNK = 1_000_000L

    private fun pick(list: List<AudioStream>): AudioStream? {
        val ok = list.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        // m4a first (plays everywhere and keeps tags), then the highest bitrate
        return ok.filter { it.format?.suffix == "m4a" }.maxByOrNull { it.averageBitrate }
            ?: ok.maxByOrNull { it.averageBitrate }
    }

    private fun fetchRange(url: String, from: Long, to: Long): Pair<ByteArray, Long> {
        val r = http.newCall(HttpRequest.Builder().url(url).header("Range", "bytes=$from-$to")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0").build()).execute()
        r.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            val total = it.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: (if (it.code == 200) it.body!!.contentLength() else -1L)
            return it.body!!.bytes() to total
        }
    }

    /** Downloads the audio into [dir] as <id>.<ext> with a sidecar (meta.json) and thumbnail. Returns the file. */
    suspend fun download(item: YtItem, dir: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        require(Regex("^[\\w-]{11}$").matches(item.id)) { "Некорректный ID видео" }
        dir.mkdirs()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=${item.id}")
        val st = pick(info.audioStreams) ?: error("Нет подходящего аудиопотока")
        val ext = st.format?.suffix ?: "m4a"
        val out = File(dir, "${item.id}.$ext")
        val part = File(dir, "${item.id}.$ext.part")
        val url = st.content
        var total = st.itagItem?.contentLength?.takeIf { it > 0 } ?: -1L
        var pos = 0L
        part.outputStream().use { os ->
            while (total < 0 || pos < total) {
                val (bytes, t) = fetchRange(url, pos, pos + CHUNK - 1)
                if (t > 0) total = t
                os.write(bytes); pos += bytes.size
                if (total > 0) onProgress(pos * 100f / total)
                if (bytes.isEmpty() || (total < 0 && bytes.size < CHUNK)) break
            }
        }
        if (!part.renameTo(out)) { part.copyTo(out, true); part.delete() }

        runCatching { // thumbnail (best effort)
            val u = info.thumbnails.maxByOrNull { it.height }?.url ?: item.thumb
            http.newCall(HttpRequest.Builder().url(u).build()).execute().use { r -> if (r.isSuccessful) File(dir, "${item.id}.jpg").writeBytes(r.body!!.bytes()) }
        }
        val (artist, title) = splitMeta(item.title, item.author)
        File(dir, "${item.id}.meta.json").writeText(JSONObject()
            .put("artist", artist).put("title", title).put("ytTitle", item.title).put("ytAuthor", item.author)
            .put("album", YT_ALBUM).put("source", "https://www.youtube.com/watch?v=${item.id}").toString())
        out
    }

    fun friendly(e: Throwable): String {
        val msg = e.message ?: e.toString()
        return if (Regex("not a bot|Sign in to confirm|SignInConfirm", RegexOption.IGNORE_CASE).containsMatchIn(msg + e.javaClass.simpleName))
            "YouTube просит подтвердить, что ты не бот — это временная блокировка по IP из-за частых запросов. Подожди немного (обычно от получаса до пары часов) или включи вход через cookies: Настройки → YouTube: вход."
        else "Не получилось скачать: $msg"
    }

    val extractorVersion: String get() = "NewPipeExtractor 0.26"
}
