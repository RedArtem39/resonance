package io.github.redartem39.resonance.data

import android.net.Uri

const val UNKNOWN_ARTIST = "Неизвестный артист"
const val NO_ALBUM = "Без альбома"
const val YT_ALBUM = "YouTube"
private val LOOSE_ALBUMS = setOf(YT_ALBUM, NO_ALBUM)
const val KEY_SEP = "‖" // separates artist and album in an album key

/** A library track. [path] is the identity: a file path (our own folder) or a SAF document URI string. */
data class Track(
    val path: String,
    val uri: Uri,
    val title: String,
    val artist: String = UNKNOWN_ARTIST,
    val album: String = NO_ALBUM,
    val albumArtist: String = UNKNOWN_ARTIST,
    val no: Int = 0,
    val disk: Int = 0,
    val year: Int = 0,
    val duration: Double = 0.0,
    val albumKey: String? = null,   // set when the user moved the track / renamed its album
    val coverUri: String? = null,   // folder art or downloaded thumbnail
    val ytId: String? = null,       // files downloaded by us are named <id>.<ext>
    val isFile: Boolean = false,    // plain file (deletable directly) vs SAF document
)

/** Albums that are not real albums (downloads, untagged files) become ONE collection each instead of one per artist. */
fun Track.key(): String = albumKey ?: if (album in LOOSE_ALBUMS) album else albumArtist + KEY_SEP + album

fun List<Track>.sortedLibrary(): List<Track> = sortedWith(
    compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.albumArtist }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }
        .thenBy { it.disk }.thenBy { it.no }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
)

fun fmtTime(s: Double): String {
    val t = s.coerceAtLeast(0.0).let { if (it.isNaN()) 0 else it.toInt() }
    return "${t / 60}:${(t % 60).toString().padStart(2, '0')}"
}

fun plural(n: Int, a: String, b: String, c: String): String {
    val m = n % 100; val k = n % 10
    return if (m in 11..19) c else if (k == 1) a else if (k in 2..4) b else c
}

fun hueOf(s: String): Float { var h = 0; for (ch in s) h = (h * 31 + ch.code) % 360; return h.toFloat() }

/** "Artist - Title" split on the first dash that is NOT inside brackets, so "Song (Hardstyle - Sped Up)" stays whole. */
fun splitMeta(rawTitle: String, author: String): Pair<String, String> {
    val t = rawTitle.replace(Regex("\\s*[(\\[](official|lyrics?|lyric video|audio|video|music video|hd|hq|4k|visuali[sz]er|remaster(ed)?)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "").trim()
    var depth = 0; var cut = -1
    for (i in 1 until t.length - 1) {
        val ch = t[i]
        if (ch in "([{") depth++
        else if (ch in ")]}") depth = maxOf(0, depth - 1)
        else if (depth == 0 && ch in "-–—" && t[i - 1] == ' ' && t[i + 1] == ' ') { cut = i; break }
    }
    val artist = (if (cut > 0) t.substring(0, cut) else author).replace(Regex("\\s*-\\s*Topic$|VEVO$", RegexOption.IGNORE_CASE), "").trim()
    return artist to (if (cut > 0) t.substring(cut + 1).trim() else t)
}
