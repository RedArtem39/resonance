@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package io.github.redartem39.resonance.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.redartem39.resonance.AppVm
import io.github.redartem39.resonance.Filter
import io.github.redartem39.resonance.View
import io.github.redartem39.resonance.data.Track
import io.github.redartem39.resonance.data.YtItem
import io.github.redartem39.resonance.data.fmtTime
import io.github.redartem39.resonance.data.hueOf
import io.github.redartem39.resonance.data.key
import io.github.redartem39.resonance.data.plural

fun visibleTracks(vm: AppVm): List<Track> {
    var list = vm.tracks
    if (vm.view == View.Favs) list = list.filter { it.path in vm.favs }
    val f = vm.filter
    if (f?.kind == "album") list = list.filter { it.key() == f.value }
    if (f?.kind == "artist") list = list.filter { it.artist == f.value || it.albumArtist == f.value }
    return list
}

// ============================================================ shared pieces
@Composable
fun Empty(icon: String, title: String, text: String, button: Pair<String, () -> Unit>? = null, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(140.dp).graphicsLayer { shape = CookieShape(0.06f); clip = true }.background(pal.primaryContainer), contentAlignment = Alignment.Center) {
            Ico(icon, pal.onPrimaryContainer, 56.dp)
        }
        Box(Modifier.height(2.dp))
        T(title, pal.onSurface, 26f, 700, spacing = -0.5f, maxLines = 2)
        T(text, pal.onSurfaceVar, 14.5f, 500, maxLines = 6, lineHeight = 21f, align = androidx.compose.ui.text.style.TextAlign.Center)
        if (button != null) {
            Morph(Modifier.height(52.dp), pal.primary, 26.dp, 16.dp, onClick = button.second) {
                Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ico("folder", pal.onPrimary, 20.dp); T(button.first, pal.onPrimary, 15f, 650)
                }
            }
        }
    }
}

@Composable
fun TrackRow(vm: AppVm, t: Track, i: Int, n: Int, list: List<Track>, wide: Boolean) {
    val pal = LocalPal.current
    val act = vm.current?.path == t.path
    val (top, bottom) = cornersFor(i, n, 24.dp)
    val fg = if (act) pal.onPrimaryContainer else pal.onSurface
    val sub = if (act) pal.onPrimaryContainer.copy(alpha = 0.8f) else pal.onSurfaceVar
    Morph(
        Modifier.fillMaxWidth().height(64.dp), if (act) pal.primaryContainer else pal.surfaceMid, if (act) 28.dp else 20.dp, 20.dp,
        topBase = if (act) 28.dp else top, bottomBase = if (act) 28.dp else bottom, pressScale = 0.985f, align = Alignment.CenterStart,
        onClick = { vm.playQueue(list, i) }, onLongClick = { vm.openTrackMenu(t, list, i) },
    ) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val artR by animateDpAsState(if (act) 22.dp else 14.dp, springy(), label = "art")
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(artR)).background(if (act) pal.primary else pal.tertiaryContainer), contentAlignment = Alignment.Center) {
                T(t.title.firstOrNull()?.uppercase() ?: "♪", if (act) pal.onPrimary else pal.onTertiaryContainer, 15f, 700)
            }
            Column(Modifier.weight(2f)) { T(t.title, fg, 15f, 600); T(t.artist, sub, 13f, 500) }
            if (wide) T(t.album, sub, 13f, 500, Modifier.weight(1.2f))
            Morph(Modifier.size(36.dp), Color.Transparent, 12.dp, 18.dp, pressScale = 0.88f, onClick = { vm.enqueue(t.path) }) {
                Ico("queueAdd", fg.copy(alpha = 0.7f), 20.dp)
            }
            T(fmtTime(t.duration), sub, 13f, 500, Modifier.width(44.dp), align = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
fun TrackList(vm: AppVm, list: List<Track>, wide: Boolean, header: (@Composable () -> Unit)? = null, bottomPad: Dp = 24.dp) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPad), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (header != null) item { header() }
        itemsIndexed(list, key = { _, t -> t.path }) { i, t -> TrackRow(vm, t, i, list.size, list, wide) }
    }
}

// ============================================================ library views
@Composable
fun LibraryScreen(vm: AppVm, wide: Boolean, pickFolder: () -> Unit) {
    val pal = LocalPal.current
    val list = remember(vm.tracks, vm.view, vm.filter, vm.favs) { visibleTracks(vm) }
    vm.currentShown = list
    val f = vm.filter
    when {
        vm.tracks.isEmpty() && !vm.scanning -> Empty("music", "Добавь свою музыку", "Выбери папку с треками — или найди трек через поиск и скачай с YouTube.", "Выбрать папку" to pickFolder)
        vm.view == View.Favs && list.isEmpty() -> Empty("heart", "Пока пусто", "Нажми на сердечко в плеере, чтобы добавить трек в избранное.")
        vm.view == View.Albums && f == null -> {
            val groups = remember(list) { list.groupBy { it.key() }.toList() }
            LazyVerticalGrid(GridCells.Adaptive(if (wide) 190.dp else 150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(groups, key = { it.first }) { (k, ts) -> AlbumCard(vm, k, ts) }
            }
        }
        vm.view == View.Artists && f == null -> {
            val groups = remember(list) { list.groupBy { it.albumArtist }.toList().sortedBy { it.first.lowercase() } }
            LazyVerticalGrid(GridCells.Adaptive(if (wide) 260.dp else 300.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(groups, key = { it.first }) { (name, ts) -> ArtistCard(vm, name, ts.size) }
            }
        }
        else -> TrackList(vm, list, wide)
    }
}

@Composable
private fun AlbumCard(vm: AppVm, key: String, ts: List<Track>) {
    val pal = LocalPal.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val r by animateDpAsState(if (down) 80.dp else 32.dp, springy(), label = "r")
    val sc by animateFloatAsState(if (down) 0.95f else 1f, springy(), label = "sc")
    Column(
        Modifier.fillMaxWidth().combinedClickableCompat(src, { vm.filter = Filter("album", key, ts[0].album); vm.view = View.Tracks }, { vm.openAlbumMenu(key) }),
    ) {
        CoverTile(ts[0], Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(r)), pal.tertiaryContainer, pal.onTertiaryContainer, 360)
        Box(Modifier.height(12.dp))
        T(ts[0].album, pal.onSurface, 15f, 650)
        T("${if (ts.map { it.albumArtist }.toSet().size > 1) "Разные артисты" else ts[0].albumArtist} · ${ts.size}", pal.onSurfaceVar, 13f, 500)
    }
}

@Composable
private fun ArtistCard(vm: AppVm, name: String, count: Int) {
    val pal = LocalPal.current
    val ah = hueOf(name).toDouble()
    val avBg = if (pal.dark) Oklch.color(.38, .09, ah) else Oklch.color(.82, .09, ah)
    val avFg = if (pal.dark) Oklch.color(.92, .04, ah) else Oklch.color(.25, .05, ah)
    Morph(Modifier.fillMaxWidth(), pal.surfaceMid, 28.dp, 40.dp, pressScale = 0.97f, align = Alignment.CenterStart,
        onClick = { vm.filter = Filter("artist", name, name); vm.view = View.Tracks }, onLongClick = { vm.openArtistMenu(name) }) {
        Row(Modifier.padding(start = 14.dp, end = 20.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(avBg), contentAlignment = Alignment.Center) { T(name.firstOrNull()?.uppercase() ?: "?", avFg, 20f, 700) }
            Column { T(name, pal.onSurface, 16f, 650); T("$count ${plural(count, "трек", "трека", "треков")}", pal.onSurfaceVar, 13f, 500) }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(src: androidx.compose.foundation.interaction.MutableInteractionSource, click: () -> Unit, long: () -> Unit): Modifier =
    this.then(Modifier.combinedClickable(src, null, onClick = click, onLongClick = long))

// ============================================================ search: local + (optional) YouTube
@Composable
fun SearchScreen(vm: AppVm, wide: Boolean) {
    val pal = LocalPal.current
    val q = vm.query.trim().lowercase()
    val local = remember(vm.tracks, q) { vm.tracks.filter { "${it.title} ${it.artist} ${it.album}".lowercase().contains(q) } }
    vm.currentShown = local
    val y = vm.yt
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (local.isNotEmpty()) {
            item { Sec("music", "В библиотеке") }
            val shown = local.take(80)
            itemsIndexed(shown, key = { _, t -> "l" + t.path }) { i, t -> TrackRow(vm, t, i, shown.size, local, wide) }
        } else if (!vm.settings.yt) {
            item { Empty("search", "Ничего не найдено", "Включи поиск на YouTube в настройках, чтобы искать и в сети.", modifier = Modifier.height(320.dp)) }
        }
        if (vm.settings.yt) {
            item { Box(Modifier.height(8.dp)); Sec("video", "YouTube") }
            when {
                y.status == "loading" && y.items.isEmpty() -> items(4) { Skeleton() }
                y.status == "err" -> item { Hint("Не удалось выполнить поиск: ${y.err}") }
                y.status == "idle" -> item { if (vm.query.trim().length < 2) Hint("Введи хотя бы 2 символа") }
                else -> {
                    if (y.items.isEmpty()) item { Hint("Ничего не нашлось") }
                    itemsIndexed(y.items, key = { _, v -> "y" + v.id }) { i, v -> YtRow(vm, v, i, y.items.size, stale = y.status == "loading") }
                }
            }
        }
    }
}

@Composable
private fun Sec(icon: String, label: String) {
    val pal = LocalPal.current
    Row(Modifier.padding(top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Ico(icon, pal.primary, 22.dp); T(label, pal.onSurface, 18f, 700, spacing = -0.2f)
    }
}

@Composable
fun Hint(text: String) { T(text, LocalPal.current.onSurfaceVar, 13f, 500, Modifier.padding(4.dp), maxLines = 8, lineHeight = 19f) }

@Composable
private fun shimmerBrush(): Brush {
    val pal = LocalPal.current
    val t = rememberInfiniteTransition(label = "sh")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "x")
    return Brush.linearGradient(listOf(pal.surfaceHigh, pal.surfaceHighest, pal.surfaceHigh), Offset(-600f + 1200f * x, 0f), Offset(600f * x + 300f, 0f))
}

@Composable
private fun Skeleton() {
    val pal = LocalPal.current
    val b = shimmerBrush()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(pal.surfaceMid).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(104.dp, 58.dp).clip(RoundedCornerShape(12.dp)).background(b))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.7f).height(16.dp).clip(RoundedCornerShape(8.dp)).background(b))
            Box(Modifier.fillMaxWidth(0.35f).height(12.dp).clip(RoundedCornerShape(8.dp)).background(b))
        }
    }
}

@Composable
private fun YtRow(vm: AppVm, v: YtItem, i: Int, n: Int, stale: Boolean) {
    val pal = LocalPal.current
    val (top, bottom) = cornersFor(i, n, 24.dp)
    val st = vm.dl[v.id] ?: if (vm.localYt(v.id) != null) io.github.redartem39.resonance.Dl("done") else io.github.redartem39.resonance.Dl("idle")
    Morph(Modifier.fillMaxWidth().graphicsLayer { alpha = if (stale) 0.5f else 1f }, pal.surfaceMid, 22.dp, 22.dp, topBase = top, bottomBase = bottom, pressScale = 0.985f, align = Alignment.CenterStart,
        onClick = { vm.ytGet(v.id) }, onLongClick = { vm.openYoutubeMenu(v.id, v.title) }) {
        Row(Modifier.padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(104.dp, 58.dp).clip(RoundedCornerShape(12.dp)).background(pal.surfaceHigh)) {
                AsyncImage(v.thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                    T(fmtTime(v.seconds.toDouble()), Color.White, 11f, 650)
                }
            }
            Column(Modifier.weight(1f)) { T(v.title, pal.onSurface, 15f, 600, maxLines = 2); T(v.author, pal.onSurfaceVar, 13f, 500) }
            DlButton(st.state, st.pct)
        }
    }
}

@Composable
private fun DlButton(state: String, pct: Float) {
    val pal = LocalPal.current
    val dlState = state == "dl"
    val bg = when (state) { "done" -> pal.primary; "err" -> Oklch.color(.40, .12, 25.0); else -> pal.secondaryContainer }
    val fg = when (state) { "done" -> pal.onPrimary; "err" -> Oklch.color(.92, .04, 25.0); else -> pal.onSecondaryContainer }
    val r by animateDpAsState(if (dlState) 22.dp else 16.dp, springy(), label = "r")
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(r)).background(if (dlState) Color.Transparent else bg), contentAlignment = Alignment.Center) {
        if (dlState) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(pal.surfaceHighest)
                drawArc(pal.primary, -90f, 360f * pct / 100f, true)
                drawCircle(pal.surfaceHigh, size.minDimension / 2 - 4.dp.toPx())
            }
            T("${pct.toInt()}", pal.onSurface, 12f, 700)
        } else Ico(when (state) { "done" -> "play"; "err" -> "refresh"; else -> "download" }, fg, 24.dp)
    }
}
