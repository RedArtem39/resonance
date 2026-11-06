package io.github.redartem39.resonance

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.redartem39.resonance.audio.ChannelTest
import io.github.redartem39.resonance.audio.CUSTOM_PRESET
import io.github.redartem39.resonance.audio.EQ_PRESETS
import io.github.redartem39.resonance.audio.Fx
import io.github.redartem39.resonance.audio.FxParams
import io.github.redartem39.resonance.audio.PlaybackService
import io.github.redartem39.resonance.data.CoverProvider
import io.github.redartem39.resonance.data.Covers
import io.github.redartem39.resonance.data.KEY_SEP
import io.github.redartem39.resonance.data.LyricCandidate
import io.github.redartem39.resonance.data.LyricLine
import io.github.redartem39.resonance.data.Library
import io.github.redartem39.resonance.data.LyricsQuery
import io.github.redartem39.resonance.data.Lyrics
import io.github.redartem39.resonance.data.Root
import io.github.redartem39.resonance.data.Store
import io.github.redartem39.resonance.data.Track
import io.github.redartem39.resonance.data.UpdateInfo
import io.github.redartem39.resonance.data.Updater
import io.github.redartem39.resonance.data.Yt
import io.github.redartem39.resonance.data.YtItem
import io.github.redartem39.resonance.data.key
import io.github.redartem39.resonance.data.sortedLibrary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.PI

enum class View(val title: String) { Tracks("Треки"), Albums("Альбомы"), Artists("Артисты"), Favs("Избранное"), Eq("Звук"), Settings("Настройки") }
data class Filter(val kind: String, val value: String, val label: String)
data class AppSettings(val yt: Boolean = true, val autoplay: Boolean = true, val updates: Boolean = true, val keepPlaying: Boolean = true, val fx: Boolean = true, val ytCookies: Boolean = true)
data class Dl(val state: String, val pct: Float = 0f) // state: idle | dl | done | err
data class YtState(val q: String = "", val status: String = "idle", val items: List<YtItem> = emptyList(), val err: String = "")

sealed interface Lyr {
    data object None : Lyr
    data object Loading : Lyr
    data object NotFound : Lyr
    class Lines(val lines: List<LyricLine>, val synced: Boolean) : Lyr
}

class MenuItem(val label: String, val danger: Boolean = false, val disabled: Boolean = false, val sep: Boolean = false, val run: () -> Unit = {})
class MenuReq(val title: String, val items: List<MenuItem>)
class DialogItem(val label: String, val sub: String = "")
class DialogReq(val title: String, val text: String, val ok: String = "OK", val danger: Boolean = false, val input: String? = null, val list: List<DialogItem>? = null)
class DialogResult(val ok: Boolean, val value: String = "", val index: Int = -1)

val SPEEDS = floatArrayOf(1f, 1.25f, 1.5f, 0.75f)
val SLEEPS = intArrayOf(0, 15, 30, 60)

class AppVm(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------ library state
    var tracks by mutableStateOf<List<Track>>(emptyList()); private set
    var scanning by mutableStateOf(false); private set
    var scanCount by mutableIntStateOf(0); private set
    var folders by mutableStateOf(Store.strings("folders")); private set
    var favs by mutableStateOf(Store.strings("favs").toSet()); private set
    private val byPath = HashMap<String, Track>()
    private val ytIdx = HashMap<String, Track>()
    fun track(path: String?): Track? = path?.let { byPath[it] }
    fun localYt(id: String): Track? = ytIdx[id]
    val downloadDir: File by lazy { Library.downloadDir(ctx) }

    // ------------------------------------------------------------ navigation
    var tab by mutableStateOf("now")          // right panel: now | lyrics | queue
    var nowOpen by mutableStateOf(false)      // compact layout: the full-screen player
    var currentShown: List<Track> = emptyList() // what the library currently lists, so play starts it when the queue is empty
    var view by mutableStateOf(View.Tracks)
    var filter by mutableStateOf<Filter?>(null)
    var query by mutableStateOf("")
    var darkTheme by mutableStateOf(Store.string("theme", "dark") == "dark")
    var settings by mutableStateOf(AppSettings(
        Store.bool("set.yt", true), Store.bool("set.autoplay", true), Store.bool("set.updates", true), Store.bool("set.keepPlaying", true),
        Store.bool("set.fx", true), Store.bool("set.ytCookies", true),
    ))
    fun applySettings(s: AppSettings) {
        settings = s
        Store.put("set.yt", s.yt); Store.put("set.autoplay", s.autoplay); Store.put("set.updates", s.updates); Store.put("set.keepPlaying", s.keepPlaying)
        Store.put("set.fx", s.fx); Store.put("set.ytCookies", s.ytCookies); Yt.useCookies = s.ytCookies
    }
    fun setTheme(dark: Boolean) { darkTheme = dark; Store.put("theme", if (dark) "dark" else "light") }

    // ------------------------------------------------------------ overlays (toast / menu / dialog)
    var toastMsg by mutableStateOf<String?>(null); private set
    private var toastJob: Job? = null
    fun toast(msg: String, ms: Long = 3400) {
        toastMsg = msg
        toastJob?.cancel()
        toastJob = viewModelScope.launch { delay(ms); toastMsg = null }
    }
    var menu by mutableStateOf<MenuReq?>(null)
    var dialog by mutableStateOf<DialogReq?>(null); private set
    private var dialogWait: CompletableDeferred<DialogResult>? = null
    suspend fun modal(req: DialogReq): DialogResult {
        dialogWait?.complete(DialogResult(false))
        val d = CompletableDeferred<DialogResult>()
        dialogWait = d; dialog = req
        return d.await()
    }
    fun closeDialog(r: DialogResult) { dialog = null; dialogWait?.complete(r); dialogWait = null }

    // ------------------------------------------------------------ player (MediaController -> PlaybackService)
    private var controller: MediaController? = null
    var queue by mutableStateOf<List<String>>(emptyList()); private set
    var index by mutableIntStateOf(-1); private set
    var playing by mutableStateOf(false); private set
    var shuffle by mutableStateOf(false); private set
    var repeat by mutableIntStateOf(0); private set // 0 off, 1 all, 2 one
    var speedI by mutableIntStateOf(0); private set
    var volume by mutableFloatStateOf(Store.float("volume", 0.8f)); private set
    var durationMs by mutableStateOf(0L); private set
    var ready by mutableStateOf(false); private set
    val current: Track? get() = queue.getOrNull(index)?.let { byPath[it] }
    private var pending: (() -> Unit)? = null

    fun positionMs(): Long = controller?.currentPosition ?: 0L
    fun durationSec(): Double = (if (durationMs > 0) durationMs else ((current?.duration ?: 0.0) * 1000).toLong()) / 1000.0

    init {
        Yt.useCookies = settings.ytCookies
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.volume = volume
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { refresh() }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) { c.pause(); c.seekTo(c.currentMediaItemIndex, 0) } // end of the queue with repeat off: stop at 0:00, like the desktop player
                }
            })
            refresh(); ready = true
            pending?.invoke(); pending = null
        }, ContextCompat.getMainExecutor(ctx))
    }

    private var lastKey: String? = null
    private fun refresh() {
        val c = controller ?: return
        playing = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)
        shuffle = c.shuffleModeEnabled
        repeat = when (c.repeatMode) { Player.REPEAT_MODE_ALL -> 1; Player.REPEAT_MODE_ONE -> 2; else -> 0 }
        durationMs = c.duration.takeIf { it > 0 } ?: 0L
        val q = List(c.mediaItemCount) { c.getMediaItemAt(it).mediaId }
        if (q != queue) queue = q
        index = if (q.isEmpty()) -1 else c.currentMediaItemIndex
        val key = queue.getOrNull(index)
        if (key != lastKey) { lastKey = key; onTrackChanged(byPath[key]) }
    }

    private fun mediaItem(t: Track) = MediaItem.Builder().setMediaId(t.path).setUri(t.uri).setMediaMetadata(
        MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album).setArtworkUri(CoverProvider.uriFor(t)).build(),
    ).build()

    private fun withController(block: (MediaController) -> Unit) { val c = controller; if (c != null) block(c) else pending = { controller?.let(block) } }

    fun playQueue(list: List<Track>, start: Int) = withController { c ->
        if (list.isEmpty()) return@withController
        c.setMediaItems(list.map(::mediaItem), start.coerceIn(0, list.lastIndex), 0)
        c.setPlaybackSpeed(SPEEDS[speedI])
        c.prepare(); c.play()
    }
    fun toggle(shownList: List<Track> = emptyList()) = withController { c ->
        if (c.mediaItemCount == 0) { if (shownList.isNotEmpty()) playQueue(shownList, 0); return@withController }
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.isPlaying) c.pause() else c.play()
    }
    fun next() = withController { c ->
        if (c.hasNextMediaItem() || repeat != 0) c.seekToNextMediaItem() else { c.pause(); c.seekTo(c.currentMediaItemIndex, 0) }
    }
    fun prev() = withController { c -> if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms.coerceAtLeast(0)) }
    fun jumpTo(i: Int) = withController { it.seekTo(i, 0); it.play() }
    fun enqueue(path: String) {
        val t = byPath[path] ?: return
        withController { c ->
            if (c.mediaItemCount == 0) playQueue(listOf(t), 0) else { c.addMediaItem(mediaItem(t)); toast("Добавлено в очередь", 1800) }
        }
    }
    fun enqueueAll(list: List<Track>) = withController { c ->
        if (c.mediaItemCount == 0) playQueue(list, 0) else { c.addMediaItems(list.map(::mediaItem)) }
        toast("В очередь: ${list.size}", 1800)
    }
    fun playNext(path: String) {
        val t = byPath[path] ?: return
        withController { c ->
            if (c.mediaItemCount == 0) { playQueue(listOf(t), 0); return@withController }
            c.addMediaItem(c.currentMediaItemIndex + 1, mediaItem(t)); toast("Сыграет следующим", 1800)
        }
    }
    fun removeFromQueue(i: Int) = withController { it.removeMediaItem(i) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (repeat) { 0 -> Player.REPEAT_MODE_ALL; 1 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
    }
    fun cycleSpeed() { speedI = (speedI + 1) % SPEEDS.size; withController { it.setPlaybackSpeed(SPEEDS[speedI]) } }
    fun changeVolume(v: Float) { volume = v; Store.put("volume", v); withController { it.volume = v } }
    fun stopPlayback() = withController { it.pause(); it.clearMediaItems() }

    // visualizer: off -> bars -> wave (solid) -> off
    var vizMode by mutableStateOf(Store.string("vizMode", "off")); private set
    fun cycleViz() { vizMode = when (vizMode) { "off" -> "bars"; "bars" -> "wave"; else -> "off" }; Store.put("vizMode", vizMode) }

    // ------------------------------------------------------------ sleep timer (fades out over 3 s, like the desktop one)
    var sleepI by mutableIntStateOf(0); private set
    var sleepLeftMin by mutableIntStateOf(0); private set
    private var sleepJob: Job? = null
    fun cycleSleep() {
        sleepI = (sleepI + 1) % SLEEPS.size
        sleepJob?.cancel(); sleepLeftMin = 0
        val mins = SLEEPS[sleepI]
        if (mins == 0) return
        val end = System.currentTimeMillis() + mins * 60_000L
        sleepLeftMin = mins
        sleepJob = viewModelScope.launch {
            while (System.currentTimeMillis() < end) { sleepLeftMin = ((end - System.currentTimeMillis() + 59_999) / 60_000).toInt(); delay(1000) }
            sleepI = 0; sleepLeftMin = 0
            val v0 = volume
            for (k in 1..30) { withController { it.volume = (v0 * (1 - k / 30f)).coerceAtLeast(0f) }; delay(100) }
            withController { it.pause(); it.volume = v0 }
        }
    }

    // ------------------------------------------------------------ current track: cover, palette, lyrics
    var cover by mutableStateOf<ImageBitmap?>(null); private set
    var hue by mutableFloatStateOf(285f); private set
    var chroma by mutableFloatStateOf(0.1f); private set
    var lyr by mutableStateOf<Lyr>(Lyr.None); private set
    var lyQuery: LyricsQuery? = null; private set
    private var trackJob: Job? = null
    private var lyJob: Job? = null

    private fun onTrackChanged(t: Track?) {
        trackJob?.cancel()
        if (t == null) { cover = null; lyr = Lyr.None; lyQuery = null; return }
        loadLyrics(t)
        trackJob = viewModelScope.launch {
            val bmp = Covers.load(ctx, t)
            cover = bmp?.asImageBitmap()
            val p = bmp?.let { withContext(Dispatchers.Default) { paletteFrom(it) } }
            if (p != null) { hue = p.first; chroma = p.second } else { hue = io.github.redartem39.resonance.data.hueOf(t.album + t.title); chroma = 0.09f }
        }
    }

    private fun toOklab(r0: Int, g0: Int, b0: Int): Pair<Double, Double> {
        fun lin(v: Int): Double { val x = v / 255.0; return if (x <= 0.04045) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        val r = lin(r0); val g = lin(g0); val b = lin(b0)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return (1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s) to (0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s)
    }
    /** Weighted average of the cover's colours (vivid pixels count more) -> hue and chroma for the tonal palette. */
    private fun paletteFrom(bmp: android.graphics.Bitmap): Pair<Float, Float> {
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, 24, 24, true)
        var sa = 0.0; var sb = 0.0; var sw = 0.0
        for (y in 0 until 24) for (x in 0 until 24) {
            val px = small.getPixel(x, y)
            val (a, b) = toOklab((px shr 16) and 255, (px shr 8) and 255, px and 255)
            val w = a * a + b * b + 0.0005
            sa += a * w; sb += b * w; sw += w
        }
        val a = sa / sw; val b = sb / sw
        return (((atan2(b, a) * 180 / PI + 360) % 360).toFloat()) to (hypot(a, b) * 1.15).coerceIn(0.012, 0.17).toFloat()
    }

    private fun loadLyrics(t: Track) {
        lyJob?.cancel()
        lyr = Lyr.Loading
        val q = LyricsQuery(t.artist, t.title, t.album, if (t.duration > 0) t.duration else durationSec())
        lyQuery = q
        lyJob = viewModelScope.launch {
            val res = Lyrics.load(ctx, q)
            lyr = when {
                res?.synced != null && Lyrics.parseLrc(res.synced).isNotEmpty() ->
                    Lyr.Lines(Lyrics.parseLrc(res.synced).map { LyricLine(it.t * res.scale, it.text) }, true)
                res?.plain != null -> Lyr.Lines(Lyrics.parsePlain(res.plain), false)
                else -> Lyr.NotFound
            }
        }
    }

    // lyrics picker (remixes / sped-up versions are rarely in LRCLIB; the original's text is the same)
    var pickOpen by mutableStateOf(false)
    var pickQ by mutableStateOf("")
    var pickLoading by mutableStateOf(false); private set
    var pickErr by mutableStateOf<String?>(null); private set
    var pickList by mutableStateOf<List<LyricCandidate>>(emptyList()); private set
    var pickScale by mutableStateOf(true)
    private fun stripBrackets(s: String) = s.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").replace(Regex("\\s+"), " ").trim().ifEmpty { s }
    fun openPicker() {
        val t = current ?: return
        pickOpen = true; pickQ = stripBrackets(t.title); runPick()
    }
    fun runPick() {
        val q = pickQ.trim(); if (q.isEmpty()) return
        pickLoading = true; pickErr = null
        viewModelScope.launch {
            try { pickList = Lyrics.search(q) } catch (e: Exception) { pickErr = e.message ?: "Ошибка"; pickList = emptyList() }
            pickLoading = false
        }
    }
    fun pickCandidate(c: LyricCandidate) {
        val q = lyQuery ?: return; val t = current ?: return
        val ratio = if (q.duration > 0 && c.duration > 0) q.duration / c.duration else 1.0
        val scale = if (c.synced != null && pickScale && ratio > 0.3 && ratio < 3) ratio else 1.0
        viewModelScope.launch { Lyrics.save(ctx, q, c.synced, c.plain, scale); pickOpen = false; loadLyrics(t) }
    }
    fun lyricsQueryDuration() = lyQuery?.duration ?: 0.0

    // ------------------------------------------------------------ scanning
    private fun roots(): List<Root> = listOf<Root>(Root.Dir(downloadDir)) + folders.map { Root.Tree(Uri.parse(it)) }
    private var scanJob: Job? = null

    fun rescan() {
        scanJob?.cancel()
        scanning = true; scanCount = 0
        val acc = ArrayList<Track>()
        scanJob = viewModelScope.launch {
            try {
                Library.scan(ctx, roots()) { batch ->
                    acc += batch
                    withContext(Dispatchers.Main) {
                        scanCount = acc.size
                        for (t in batch) { byPath[t.path] = t; t.ytId?.let { ytIdx[it] = t } }
                        tracks = acc.toList()
                    }
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; toast("Сканирование: ${e.message}", 5000) }
            val sorted = acc.sortedLibrary()
            tracks = sorted
            indexTracks()
            scanning = false
            refresh()
        }
    }

    private fun indexTracks() { byPath.clear(); ytIdx.clear(); for (t in tracks) { byPath[t.path] = t; t.ytId?.let { ytIdx[it] = t } } }
    private fun applyTracks(list: List<Track>) { tracks = list; indexTracks() }

    fun addFolder(uri: Uri) {
        val s = uri.toString()
        if (s in folders) return
        val rw = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try { ctx.contentResolver.takePersistableUriPermission(uri, rw) } catch (_: Exception) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { toast("Не удалось получить доступ к папке", 5000); return }
        }
        folders = folders + s; Store.putStrings("folders", folders)
        rescan()
    }
    fun removeFolder(i: Int) {
        val u = folders.getOrNull(i) ?: return
        runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(u), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        folders = folders.filterIndexed { k, _ -> k != i }; Store.putStrings("folders", folders)
        rescan()
    }
    fun folderName(u: String): String = runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(u))?.name }.getOrNull()
        ?: Uri.parse(u).lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: u

    // ------------------------------------------------------------ favourites
    fun toggleFav(path: String) {
        favs = if (path in favs) favs - path else favs + path
        Store.putStrings("favs", favs)
    }

    // ------------------------------------------------------------ edits / delete
    fun patchTrack(t: Track, patch: Map<String, String>) {
        Library.updateTrack(ctx, t.path, patch)
        val n = t.copy(
            title = patch["title"] ?: t.title, artist = patch["artist"] ?: t.artist,
            album = patch["album"] ?: t.album, albumKey = patch["albumKey"] ?: t.albumKey,
        )
        applyTracks(tracks.map { if (it.path == t.path) n else it }.sortedLibrary())
        if (current?.path == t.path && "title" in patch) loadLyrics(n)
    }

    suspend fun renameTrack(t: Track) {
        val r = modal(DialogReq("Изменить название", "Новое название трека:", ok = "Далее", input = t.title))
        if (!r.ok || r.value.isBlank() || r.value == t.title) return
        val c = modal(DialogReq("Переименовать трек?", "«${t.title}» → «${r.value}»", ok = "Переименовать"))
        if (!c.ok) return
        patchTrack(t, mapOf("title" to r.value)); toast("Название изменено", 1800)
    }

    data class AlbumGroup(val key: String, val name: String, val tracks: List<Track>, val sub: String)
    fun albumGroups(): List<AlbumGroup> = tracks.groupBy { it.key() }.map { (k, ts) ->
        AlbumGroup(k, ts[0].album, ts, "${if (ts.map { it.albumArtist }.toSet().size > 1) "Разные артисты" else ts[0].albumArtist} · ${ts.size}")
    }

    suspend fun moveToAlbum(t: Track) {
        val groups = albumGroups().filter { it.key != t.key() }
        val r = modal(DialogReq("В альбом", "Куда переместить «${t.title}»?", ok = "Переместить",
            list = listOf(DialogItem("＋ Новый альбом…", "Создать свою подборку")) + groups.map { DialogItem(it.name, it.sub) }))
        if (!r.ok) return
        if (r.index == 0) {
            val n = modal(DialogReq("Новый альбом", "Название альбома:", ok = "Создать", input = ""))
            if (!n.ok || n.value.isBlank()) return
            patchTrack(t, mapOf("album" to n.value, "albumKey" to "user$KEY_SEP${n.value}"))
        } else {
            val g = groups[r.index - 1]
            patchTrack(t, mapOf("album" to g.name, "albumKey" to g.key))
        }
        toast("Перемещено в альбом", 1800)
    }

    suspend fun renameAlbum(key: String) {
        val g = albumGroups().firstOrNull { it.key == key } ?: return
        val r = modal(DialogReq("Переименовать альбом", "Новое название:", ok = "Далее", input = g.name))
        if (!r.ok || r.value.isBlank() || r.value == g.name) return
        val c = modal(DialogReq("Переименовать альбом?", "«${g.name}» → «${r.value}» (треков: ${g.tracks.size})", ok = "Переименовать"))
        if (!c.ok) return
        val paths = g.tracks.map { it.path }.toSet()
        for (t in g.tracks) Library.updateTrack(ctx, t.path, mapOf("album" to r.value, "albumKey" to key)) // the key is pinned, so the group stays together
        applyTracks(tracks.map { if (it.path in paths) it.copy(album = r.value, albumKey = key) else it }.sortedLibrary())
        toast("Альбом переименован", 1800)
    }

    suspend fun deleteTrack(t: Track) {
        val r = modal(DialogReq("Удалить трек?", "«${t.title}» будет удалён с устройства. В Android нет корзины для файлов приложений, поэтому вернуть его не получится.", ok = "Удалить", danger = true))
        if (!r.ok) return
        val wasCurrent = current?.path == t.path
        if (wasCurrent) withController { it.pause() }
        val res = withContext(Dispatchers.IO) { Library.deleteTrack(ctx, t) }
        if (res.isFailure) { toast("Не удалось удалить: ${res.exceptionOrNull()?.message}", 6000); return }
        withController { c ->
            for (i in c.mediaItemCount - 1 downTo 0) if (c.getMediaItemAt(i).mediaId == t.path) c.removeMediaItem(i)
        }
        t.ytId?.let { dl.remove(it) }
        if (t.path in favs) { favs = favs - t.path; Store.putStrings("favs", favs) }
        applyTracks(tracks.filter { it.path != t.path })
        toast("Трек удалён", 2500)
    }

    // ------------------------------------------------------------ YouTube search + download
    var yt by mutableStateOf(YtState())
    val dl = mutableStateMapOf<String, Dl>()
    private var ytJob: Job? = null

    fun onQuery(q: String) {
        query = q
        ytJob?.cancel()
        if (query.trim().length < 2 || !settings.yt) { yt = YtState(); return }
        yt = yt.copy(status = "loading")
        val text = query.trim()
        ytJob = viewModelScope.launch {
            delay(550)
            yt = try { YtState(text, "ok", Yt.search(text)) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; YtState(text, "err", err = e.message ?: e.toString()) }
        }
    }
    fun clearSearch() { query = ""; ytJob?.cancel(); yt = YtState() }

    fun ytGet(id: String) {
        ytIdx[id]?.let { playQueue(listOf(it), 0); return }
        if (dl[id]?.state == "dl") return
        val item = yt.items.firstOrNull { it.id == id } ?: return
        dl[id] = Dl("dl", 0f)
        viewModelScope.launch {
            try {
                val f = Yt.download(item, downloadDir) { p -> dl[id] = Dl("dl", p) }
                // pick the new file up through the normal scan path, so it carries the sidecar title/artist and thumbnail
                var found: Track? = null
                Library.scan(ctx, listOf(Root.Dir(downloadDir))) { b -> b.firstOrNull { it.path == f.absolutePath }?.let { found = it } }
                val t = found ?: error("Файл не найден после загрузки")
                applyTracks((tracks.filter { it.path != t.path } + t).sortedLibrary())
                dl[id] = Dl("done")
                if (settings.autoplay) playQueue(listOf(t), 0)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                dl[id] = Dl("err")
                val bot = Regex("not a bot|Sign in to confirm|SignIn", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty() + e.javaClass.simpleName)
                toast(Yt.friendly(e), if (bot) 10000 else 6000)
            }
        }
    }

    var cookieStatus by mutableStateOf(Yt.cookieInfo()); private set
    fun importCookies(text: String) {
        try { val n = Yt.importCookies(text); applySettings(settings.copy(ytCookies = true)); toast("Cookies загружены: $n", 3000) } catch (e: Exception) { toast("Не получилось: ${e.message}", 6000) }
        cookieStatus = Yt.cookieInfo()
    }
    fun removeCookies() { Yt.removeCookies(); toast("Cookies удалены", 2500); cookieStatus = Yt.cookieInfo() }


    // ------------------------------------------------------------ context menus (what each long-press offers)
    fun openTrackMenu(t: Track, list: List<Track>? = null, index: Int = 0, queueIndex: Int? = null) {
        val fav = t.path in favs
        val items = ArrayList<MenuItem>()
        items += MenuItem("Играть") { if (list != null) playQueue(list, index) else playQueue(listOf(t), 0) }
        items += MenuItem("Играть следующим") { playNext(t.path) }
        items += MenuItem("Добавить в очередь") { enqueue(t.path) }
        if (queueIndex != null && queueIndex != this.index) items += MenuItem("Убрать из очереди") { removeFromQueue(queueIndex) }
        items += MenuItem(if (fav) "Убрать из избранного" else "В избранное") { toggleFav(t.path) }
        items += MenuItem("", sep = true)
        if (current?.path == t.path) items += MenuItem("Подобрать текст…") { tab = "lyrics"; openPicker() }
        items += MenuItem("В альбом…") { viewModelScope.launch { moveToAlbum(t) } }
        items += MenuItem("Изменить название…") { viewModelScope.launch { renameTrack(t) } }
        items += MenuItem("", sep = true)
        items += MenuItem("Удалить…", danger = true) { viewModelScope.launch { deleteTrack(t) } }
        menu = MenuReq(t.title, items)
    }

    fun openAlbumMenu(key: String) {
        val g = albumGroups().firstOrNull { it.key == key } ?: return
        menu = MenuReq(g.name, listOf(
            MenuItem("Открыть") { filter = Filter("album", key, g.name); view = View.Tracks },
            MenuItem("Играть альбом") { playQueue(g.tracks, 0) },
            MenuItem("Добавить в очередь") { enqueueAll(g.tracks) },
            MenuItem("", sep = true),
            MenuItem("Переименовать альбом…") { viewModelScope.launch { renameAlbum(key) } },
        ))
    }

    fun openArtistMenu(name: String) {
        val ts = tracks.filter { it.artist == name || it.albumArtist == name }
        menu = MenuReq(name, listOf(
            MenuItem("Открыть") { filter = Filter("artist", name, name); view = View.Tracks },
            MenuItem("Играть всё") { playQueue(ts, 0) },
            MenuItem("Добавить в очередь") { enqueueAll(ts) },
        ))
    }

    fun openYoutubeMenu(id: String, title: String) {
        val url = "https://www.youtube.com/watch?v=$id"
        val local = ytIdx[id]
        val items = arrayListOf(
            MenuItem(if (local != null) "Играть" else "Скачать и слушать") { ytGet(id) },
            MenuItem("Открыть на YouTube") { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            MenuItem("Копировать ссылку") {
                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("YouTube", url))
                toast("Ссылка скопирована", 1800)
            },
        )
        if (local != null) { items += MenuItem("", sep = true); items += MenuItem("Удалить скачанный трек…", danger = true) { viewModelScope.launch { deleteTrack(local) } } }
        menu = MenuReq(title, items)
    }

    // ------------------------------------------------------------ equalizer / sound
    var fx by mutableStateOf(FxParams()); private set
    fun fxSet(p: FxParams) { fx = p; Fx.update(p) }
    fun fxBand(i: Int, v: Float) { val b = fx.bands.copyOf(); b[i] = v; fxSet(fx.copy(bands = b, preset = CUSTOM_PRESET)) }
    fun fxPreset(name: String) { EQ_PRESETS[name]?.let { fxSet(fx.copy(bands = it.copyOf(), preset = name)) } }
    fun fxReset() { Fx.resetAudio(); fx = Fx.params }
    var testStep by mutableStateOf<String?>(null); private set
    fun channelTest() = ChannelTest.run { s -> main.post { testStep = s } }

    // ------------------------------------------------------------ updates
    var upd by mutableStateOf<UpdateInfo?>(null); private set
    var updBusy by mutableStateOf<String?>(null); private set
    var updPct by mutableIntStateOf(0); private set
    val versionName: String get() = BuildConfig.VERSION_NAME
    fun checkUpdates(manual: Boolean) {
        viewModelScope.launch {
            val u = Updater.check(versionName)
            upd = u
            if (!manual && u.ok && u.hasUpdate) toast("Доступно обновление ${u.latest} — Настройки → Обновления", 7000)
        }
    }
    fun installVersion(v: String) {
        updBusy = v; updPct = 0
        viewModelScope.launch {
            try { toast(Updater.install(ctx, v) { updPct = it }, 9000) } catch (e: Exception) { toast("Не получилось: ${e.message}", 7000) }
            updBusy = null
        }
    }

    init {
        Fx.load(); fx = Fx.params
        rescan()
        if (settings.updates) viewModelScope.launch { delay(4000); checkUpdates(false) }
    }
}
