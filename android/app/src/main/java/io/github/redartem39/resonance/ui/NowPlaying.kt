@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.github.redartem39.resonance.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.redartem39.resonance.AppVm
import io.github.redartem39.resonance.Lyr
import io.github.redartem39.resonance.SPEEDS
import io.github.redartem39.resonance.data.fmtTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Shared seek state: the wavy bar and the ring around the cover both drive it. */
class SeekState {
    var active by mutableStateOf(false)
    var p by mutableFloatStateOf(0f)
}

private fun AppVm.progress(seek: SeekState): Float =
    if (seek.active) seek.p else durationSec().let { if (it > 0) (positionMs() / 1000.0 / it).toFloat().coerceIn(0f, 1f) else 0f }

// ============================================================ the panel
@Composable
fun NowPanel(vm: AppVm, tab: String, onTab: (String) -> Unit, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null, rounded: Boolean = true) {
    val pal = LocalPal.current
    val seek = remember { SeekState() }
    Box(modifier.then(if (rounded) Modifier.clip(RoundedCornerShape(32.dp)) else Modifier).background(pal.surfaceMid)) {
        // visualizer: a full-bleed background of the bottom of the panel (the panel's rounded corners clip it)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(340.dp)) {
            val a by animateFloatAsState(if (vm.vizMode != "off") 1f else 0f, tween(450), label = "viz")
            Box(Modifier.fillMaxSize().alpha(a)) { VizLayer(if (vm.vizMode == "off" && a > 0.01f) "bars" else vm.vizMode) }
        }
        Column(Modifier.fillMaxSize().then(if (onClose != null) Modifier.windowInsetsPadding(WindowInsets.systemBars) else Modifier).padding(start = 24.dp, end = 24.dp, top = if (onClose != null) 8.dp else 30.dp, bottom = 22.dp)) {
            if (onClose != null) {
                // a big handle: the whole strip closes the player on tap (swiping down anywhere also closes it)
                Box(Modifier.fillMaxWidth().height(56.dp).combinedClickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 44.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(pal.onSurfaceVar.copy(alpha = 0.5f)))
                }
            }
            Tabs(tab, onTab)
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                Pane(tab == "now") { CoverPane(vm, seek) }
                Pane(tab == "lyrics") { LyricsPane(vm) }
                Pane(tab == "queue") { QueuePane(vm) }
            }
            Dock(vm, seek)
        }
    }
}

@Composable
private fun Tabs(tab: String, onTab: (String) -> Unit) {
    val pal = LocalPal.current
    val items = listOf("now" to "Сейчас", "lyrics" to "Текст", "queue" to "Очередь")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { i, (k, l) ->
            val on = tab == k
            val start by animateDpAsStateS(if (on || i == 0) 20.dp else 8.dp)
            val end by animateDpAsStateS(if (on || i == items.size - 1) 20.dp else 8.dp)
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end))
                    .background(if (on) pal.secondaryContainer else pal.surfaceHigh).combinedClickable(onClick = { onTab(k) }),
                contentAlignment = Alignment.Center,
            ) { T(l, if (on) pal.onSecondaryContainer else pal.onSurfaceVar, 14f, 650) }
        }
    }
}

@Composable
private fun animateDpAsStateS(target: Dp) = androidx.compose.animation.core.animateDpAsState(target, springy(), label = "dp")

/** Pane that fades and slides in (opacity .35s, translateY 14 + scale .98), and is not composed while invisible. */
@Composable
private fun Pane(visible: Boolean, content: @Composable () -> Unit) {
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(350), label = "pane")
    if (a < 0.01f && !visible) return
    val d = LocalDensity.current
    Box(Modifier.fillMaxSize().graphicsLayer {
        alpha = a; translationY = (1 - a) * 14.dp.toPx(); scaleX = 0.98f + 0.02f * a; scaleY = 0.98f + 0.02f * a
    }) { content() }
}

// ============================================================ cover with the wavy progress ring
private const val RING_LOBES = 30

@Composable
private fun CoverPane(vm: AppVm, seek: SeekState) {
    val pal = LocalPal.current
    val frame = LocalFrame.current
    val amp by animateFloatAsState(if (vm.playing) 1f else 0f, tween(600), label = "amp")
    val coverAmp by animateFloatAsState(if (vm.playing) 0.06f else 0f, tween(800), label = "cookie")
    val coverAlpha by animateFloatAsState(if (vm.cover != null) 1f else 0f, tween(500), label = "ca")
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Box(Modifier.size(side)) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val R = size.width * 0.462f
                        if (abs(hypot(down.position.x - c.x, down.position.y - c.y) - R) > size.width * 0.07f || vm.durationSec() <= 0) return@awaitEachGesture
                        fun set(o: Offset) { var a = atan2(o.y - c.y, o.x - c.x) + PI.toFloat() / 2; if (a < 0) a += (2 * PI).toFloat(); seek.p = (a / (2 * PI)).toFloat() }
                        seek.active = true; set(down.position); down.consume()
                        drag(down.id) { set(it.position); it.consume() }
                        vm.seekTo((seek.p * vm.durationSec() * 1000).toLong()); seek.active = false
                    }
                },
            ) {
                val nowMs = frame.nanos / 1_000_000f
                val p = vm.progress(seek)
                val s = size.width; val c = s / 2; val R = s * 0.462f
                val lw = max(3.dp.toPx(), s * 0.012f)
                drawCircle(pal.surfaceHighest, R, Offset(c, c), style = Stroke(lw, cap = StrokeCap.Round))
                if (p > 0.001f) {
                    val A = s * 0.011f * amp; val phase = nowMs / 380f; val a0 = -PI.toFloat() / 2; val span = (2 * PI).toFloat() * p
                    val steps = max(8, ceil(span * 60).toInt())
                    val path = Path()
                    for (i in 0..steps) {
                        val th = a0 + span * i / steps
                        val r = R + sin((th - a0) * RING_LOBES - phase) * A * min(1f, min(i / 12f, (steps - i) / 12f + 0.25f))
                        val x = c + cos(th) * r; val y = c + sin(th) * r
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, pal.primary, style = Stroke(lw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    val te = a0 + span
                    drawCircle(pal.primary, lw * 1.9f, Offset(c + cos(te) * R, c + sin(te) * R))
                }
            }
            // the cover: inset 9%, edge morphs into a cookie while playing
            Box(
                Modifier.fillMaxSize().padding(side * 0.09f).graphicsLayer { shape = CookieShape(coverAmp); clip = true }.background(pal.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                val cv = vm.cover
                if (coverAlpha < 1f) Box(Modifier.alpha(1f - coverAlpha).graphicsLayer { scaleX = 2.6f; scaleY = 2.6f }) { Ico("music", pal.onPrimaryContainer, 24.dp) }
                if (cv != null) Image(cv, null, Modifier.fillMaxSize().alpha(coverAlpha), contentScale = ContentScale.Crop)
            }
        }
    }
}

// ============================================================ lyrics
@Composable
private fun Bounce(color: Color, delay: Int, size: Dp) {
    val t = rememberInfiniteTransition(label = "b")
    val y by t.animateFloat(0f, 1f, infiniteRepeatable(keyframes { durationMillis = 1100; 0f at 0; -7f at 440; 0f at 1100 }, RepeatMode.Restart, initialStartOffset = androidx.compose.animation.core.StartOffset(delay)), label = "y")
    Box(Modifier.size(size).graphicsLayer { translationY = y.dp.toPx() }.clip(CircleShape).background(color))
}

@Composable
private fun LoadingDots() {
    val pal = LocalPal.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Bounce(pal.primary, 0, 12.dp); Bounce(pal.primary, 120, 12.dp); Bounce(pal.primary, 240, 12.dp) }
}

@Composable
private fun StateMsg(icon: String, msg: String, extra: @Composable (() -> Unit)? = null) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        if (icon == "load") LoadingDots() else Ico(icon, pal.onSurfaceVar.copy(alpha = 0.5f), 56.dp)
        T(msg, pal.onSurfaceVar, 15f, 600)
        extra?.invoke()
    }
}

@Composable
private fun LyricsPane(vm: AppVm) {
    val pal = LocalPal.current
    val frame = LocalFrame.current
    Box(Modifier.fillMaxSize()) {
        when (val l = vm.lyr) {
            Lyr.None -> StateMsg("music", "Ничего не играет")
            Lyr.Loading -> StateMsg("load", "Ищу текст…")
            Lyr.NotFound -> StateMsg("lyrics", "Текст не найден") {
                Morph(Modifier.height(40.dp), pal.primary, 20.dp, 14.dp, onClick = { vm.openPicker() }) { T("Подобрать текст", pal.onPrimary, 14f, 650, modifier = Modifier.padding(horizontal = 20.dp)) }
            }
            is Lyr.Lines -> if (l.synced) SyncedLyrics(vm, l) else PlainLyrics(l)
        }
        if (vm.current != null && vm.lyr !is Lyr.None) {
            Box(Modifier.align(Alignment.TopEnd)) { Mini("Другой текст") { vm.openPicker() } }
        }
        LyricsPicker(vm)
    }
}

@Composable
private fun PlainLyrics(l: Lyr.Lines) {
    val pal = LocalPal.current
    LazyColumn(Modifier.fillMaxSize().fadingEdges(0.06f), contentPadding = PaddingValues(top = 44.dp, bottom = 60.dp)) {
        item { T("Текст без синхронизации", pal.onSurfaceVar, 12f, 650, modifier = Modifier.padding(start = 10.dp, bottom = 12.dp)) }
        itemsIndexed(l.lines) { _, ln -> if (ln.text.isEmpty()) Box(Modifier.height(14.dp)) else T(ln.text, pal.onSurface.copy(alpha = 0.88f), 20f, 650, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), maxLines = 10) }
    }
}

/** Fades the top and bottom edges of a scrolling pane, like the CSS mask-image. */
fun Modifier.fadingEdges(frac: Float): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Transparent, frac to Color.Black, 1f - frac to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }

@Composable
private fun SyncedLyrics(vm: AppVm, l: Lyr.Lines) {
    val pal = LocalPal.current
    val frame = LocalFrame.current
    val state = rememberLazyListState()
    var active by remember(l) { mutableIntStateOf(-1) }
    var lineP by remember(l) { mutableFloatStateOf(0f) }
    var lastUser by remember { mutableStateOf(0L) }
    if (state.isScrollInProgress) lastUser = System.currentTimeMillis()

    // every frame: which line is current, and how far into it we are
    LaunchedEffect(l) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            val time = vm.positionMs() / 1000.0
            val t = time + 0.12
            var lo = 0; var hi = l.lines.size - 1; var idx = -1
            while (lo <= hi) { val mid = (lo + hi) ushr 1; if (l.lines[mid].t <= t) { idx = mid; lo = mid + 1 } else hi = mid - 1 }
            if (idx != active) active = idx
            val L = l.lines.getOrNull(idx)
            if (L != null && L.text.isNotEmpty()) {
                val next = l.lines.getOrNull(idx + 1)
                val dur = min(if (next != null) next.t - L.t else 6.0, max(1.2, L.text.length * 0.08)).coerceIn(0.6, 9.0)
                lineP = ((time - L.t) / dur).coerceIn(0.0, 1.0).toFloat()
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pad = maxHeight * 0.38f
        val dens = LocalDensity.current
        val viewportPx = with(dens) { maxHeight.toPx() }
        LaunchedEffect(active) {
            if (active < 0) return@LaunchedEffect
            if (System.currentTimeMillis() - lastUser < 2500) return@LaunchedEffect
            val h = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }?.size ?: 90
            val padPx = with(dens) { pad.toPx() }
            // an item at scroll offset 0 sits `pad` px below the top; shift it so its centre lands on the viewport centre
            state.animateScrollToItem(active, (padPx - (viewportPx / 2 - h / 2)).roundToInt())
        }
        LazyColumn(Modifier.fillMaxSize().fadingEdges(0.18f), state = state, contentPadding = PaddingValues(top = pad, bottom = pad)) {
            itemsIndexed(l.lines) { i, ln ->
                val d = abs(i - active)
                val isActive = i == active
                val sc by animateFloatAsState(if (isActive) 1f else 0.92f, springy(), label = "sc")
                val al by animateFloatAsState(if (isActive) 1f else max(0.2f, 0.62f - d * 0.1f), tween(500), label = "al")
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .graphicsLayer { scaleX = sc; scaleY = sc; transformOrigin = TransformOrigin(0f, 0.5f); alpha = al }
                        .clip(RoundedCornerShape(22.dp))
                        .combinedClickable(onClick = { vm.seekTo(((ln.t + 0.01) * 1000).toLong()); if (!vm.playing) vm.toggle() })
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    if (ln.text.isEmpty()) {
                        Row(Modifier.padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(3) { k -> if (isActive) Bounce(pal.onSurfaceVar, k * 150, 10.dp) else Box(Modifier.size(10.dp).clip(CircleShape).background(pal.onSurfaceVar)) }
                        }
                    } else {
                        LyricLineText(ln.text, isActive, i < active) { lineP }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricLineText(text: String, active: Boolean, past: Boolean, progress: () -> Float) {
    val pal = LocalPal.current
    // Spaces separate words; text without spaces (CJK) is split per character so focus still travels.
    val spaced = text.any { it.isWhitespace() }
    val toks = remember(text) { if (spaced) text.trim().split(Regex("\\s+")) else text.map { it.toString() } }
    val weights = remember(text) { toks.map { it.length + if (spaced) 1 else 0 } }
    val total = weights.sum().toFloat()
    val bounds = remember(text) { var acc = 0; weights.map { w -> val a = acc / total; acc += w; a to acc / total } }
    FlowRow(horizontalArrangement = Arrangement.Start) {
        toks.forEachIndexed { i, w ->
            val (a, b) = bounds[i]
            val f by remember(active, past) { derivedStateOf { if (past) 100 else if (!active) 0 else ((progress() - a) / (b - a)).coerceIn(0f, 1f).times(100).roundToInt() } }
            val full = pal.onSurface; val dim = pal.onSurface.copy(alpha = 0.38f)
            val e = (f * 1.16f / 100f)
            val brush = Brush.horizontalGradient(0f to full, (e - 0.16f).coerceIn(0f, 1f) to full, e.coerceIn(0f, 1f) to dim, 1f to dim)
            Text(
                if (spaced && i < toks.size - 1) "$w " else w,
                style = TextStyle(brush = brush, fontSize = 26.sp, fontWeight = FontWeight(750), letterSpacing = (-0.4).sp, lineHeight = 32.sp),
            )
        }
    }
}

@Composable
private fun LyricsPicker(vm: AppVm) {
    val pal = LocalPal.current
    val a by animateFloatAsState(if (vm.pickOpen) 1f else 0f, tween(300), label = "pk")
    if (a < 0.01f && !vm.pickOpen) return
    Column(
        Modifier.fillMaxSize().graphicsLayer { alpha = a; translationY = (1 - a) * 16.dp.toPx() }.background(pal.surfaceMid)
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T("Подобрать текст", pal.onSurface, 17f, 700)
            Mini("Закрыть") { vm.pickOpen = false }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(pal.surfaceHigh).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
                if (vm.pickQ.isEmpty()) T("Название песни", pal.onSurfaceVar, 14.5f)
                BasicTextField(
                    vm.pickQ, { vm.pickQ = it }, singleLine = true, textStyle = TextStyle(color = pal.onSurface, fontSize = 14.5.sp),
                    cursorBrush = Brush.linearGradient(listOf(pal.primary, pal.primary)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.runPick() }),
                )
            }
            Mini("Искать") { vm.runPick() }
        }
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { vm.pickScale = !vm.pickScale }), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SwitchM3(vm.pickScale) { vm.pickScale = it }
            Column(Modifier.weight(1f)) {
                T("Подогнать тайминги под длину моего трека", pal.onSurface, 13.5f, 600, maxLines = 2)
                T("Для sped up и замедленных версий. У ремикса с другой структурой лучше выключить.", pal.onSurfaceVar, 12f, 500, maxLines = 3, lineHeight = 16f)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                vm.pickLoading -> Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.TopCenter) { LoadingDots() }
                vm.pickErr != null -> T(vm.pickErr!!, pal.onSurfaceVar, 13f, 500, maxLines = 4)
                vm.pickList.isEmpty() -> T("Ничего не найдено. Попробуй изменить запрос.", pal.onSurfaceVar, 13f)
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(vm.pickList) { i, c ->
                        val (top, bottom) = cornersFor(i, vm.pickList.size, 20.dp)
                        val dur = vm.lyricsQueryDuration()
                        val ratio = if (dur > 0 && c.duration > 0) dur / c.duration else 1.0
                        val note = if (c.synced != null) "с синхронизацией" else "только текст"
                        val speed = if (c.synced != null && abs(ratio - 1) > 0.02) " · тайминги ×${"%.2f".format(ratio)}" else ""
                        Morph(Modifier.fillMaxWidth(), pal.surfaceHigh, 18.dp, 18.dp, topBase = top, bottomBase = bottom, pressScale = 0.98f, align = Alignment.CenterStart, onClick = { vm.pickCandidate(c) }) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                T("${c.title} — ${c.artist}", pal.onSurface, 14f, 600)
                                T("${fmtTime(c.duration)} · $note$speed", pal.onSurfaceVar, 12f, 500)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================ queue
@Composable
private fun QueuePane(vm: AppVm) {
    val pal = LocalPal.current
    if (vm.queue.isEmpty()) { StateMsg("music", "Очередь пуста"); return }
    val state = rememberLazyListState()
    LaunchedEffect(Unit) { if (vm.index >= 0) state.scrollToItem(max(0, vm.index - 2)) }
    LazyColumn(Modifier.fillMaxSize(), state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(vm.queue, key = { i, p -> "$i$p" }) { i, path ->
            val t = vm.track(path) ?: return@itemsIndexed
            val act = i == vm.index
            val (top, bottom) = cornersFor(i, vm.queue.size, 20.dp)
            Morph(
                Modifier.fillMaxWidth().height(56.dp), if (act) pal.primaryContainer else pal.surfaceHigh, if (act) 22.dp else 18.dp, 18.dp,
                topBase = if (act) 22.dp else top, bottomBase = if (act) 22.dp else bottom, pressScale = 0.98f, align = Alignment.CenterStart,
                onClick = { vm.jumpTo(i) }, onLongClick = { vm.openTrackMenu(t, queueIndex = i) },
            ) {
                val fg = if (act) pal.onPrimaryContainer else pal.onSurface
                val sub = if (act) pal.onPrimaryContainer.copy(alpha = 0.8f) else pal.onSurfaceVar
                Row(Modifier.padding(start = 8.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { if (act) Ico("play", fg, 20.dp) else T("${i + 1}", pal.onSurfaceVar, 13f, 650) }
                    Column(Modifier.weight(1f)) { T(t.title, fg, 14f, 600); T(t.artist, sub, 12f, 500) }
                    T(fmtTime(t.duration), sub, 12f, 500)
                }
            }
        }
    }
}

// ============================================================ dock: title, wavy seek bar, controls
@Composable
private fun Dock(vm: AppVm, seek: SeekState) {
    val pal = LocalPal.current
    val frame = LocalFrame.current
    val t = vm.current
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                T(t?.title ?: "Ничего не играет", pal.onSurface, 24f, 700, spacing = -0.5f)
                Spacer4()
                T(if (t != null) "${t.artist} · ${t.album}" else "Выбери трек в библиотеке", pal.onSurfaceVar, 14f, 500)
            }
            val fav = t != null && t.path in vm.favs
            val pop by animateFloatAsState(if (fav) 1f else 0.6f, springy(), label = "pop")
            Morph(Modifier.size(48.dp), if (fav) pal.primaryContainer else Color.Transparent, if (fav) 14.dp else 24.dp, 14.dp, onClick = { t?.let { vm.toggleFav(it.path) } }) {
                Ico("heart", if (fav) pal.primary else pal.onSurfaceVar, 24.dp, Modifier.graphicsLayer { val s = if (fav) pop else 1f; scaleX = s; scaleY = s })
            }
        }
        SeekBar(vm, seek)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            // whole seconds only: the clock recomposes once a second, not on every frame
            val shownSec by remember { derivedStateOf { frame.nanos; (if (seek.active) seek.p * vm.durationSec() else vm.positionMs() / 1000.0).toInt() } }
            T(fmtTime(shownSec.toDouble()), pal.onSurfaceVar, 12f, 600)
            T(fmtTime(vm.durationSec()), pal.onSurfaceVar, 12f, 600)
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Toggle("shuffle", vm.shuffle) { vm.toggleShuffle() }
            Ctl("prev") { vm.prev() }
            PlayButton(vm.playing) { vm.toggle(vm.currentShown) }
            Ctl("next") { vm.next() }
            Toggle(if (vm.repeat == 2) "repeatOne" else "repeat", vm.repeat > 0) { vm.cycleRepeat() }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Mini("${SPEEDS[vm.speedI].let { if (it == it.toInt().toFloat()) it.toInt().toString() else it.toString() }}×") { vm.cycleSpeed() }
            Mini(if (vm.sleepLeftMin > 0) "${vm.sleepLeftMin} мин" else "Сон", "timer", on = vm.sleepLeftMin > 0) { vm.cycleSleep() }
            Mini(null, if (vm.vizMode == "wave") "wave" else "eq", on = vm.vizMode != "off") { vm.cycleViz() }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Ico("volume", pal.onSurfaceVar, 22.dp)
                M3Slider(vm.volume, { vm.changeVolume(it) }, Modifier.weight(1f), haloColor = pal.surfaceMid)
            }
        }
    }
}

@Composable private fun Spacer4() = Box(Modifier.height(4.dp))

@Composable
private fun SeekBar(vm: AppVm, seek: SeekState) {
    val pal = LocalPal.current
    val frame = LocalFrame.current
    val amp by animateFloatAsState(if (vm.playing) 1f else 0f, tween(600), label = "amp")
    Canvas(
        Modifier.fillMaxWidth().padding(top = 12.dp).height(32.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (vm.durationSec() <= 0) return@awaitEachGesture
                fun set(x: Float) { val pad = 4.dp.toPx(); seek.p = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) }
                seek.active = true; set(down.position.x); down.consume()
                drag(down.id) { set(it.position.x); it.consume() }
                vm.seekTo((seek.p * vm.durationSec() * 1000).toLong()); seek.active = false
            }
        },
    ) {
        val nowMs = frame.nanos / 1_000_000f
        val p = vm.progress(seek)
        val w = size.width; val h = size.height; val mid = h / 2; val pad = 4.dp.toPx()
        val px = pad + (w - 2 * pad) * p
        val lw = 4.dp.toPx()
        if (px + 8.dp.toPx() < w - pad) drawLine(pal.surfaceHighest, Offset(px + 8.dp.toPx(), mid), Offset(w - pad, mid), lw, StrokeCap.Round)
        val path = Path()
        val A = 4.5.dp.toPx() * amp; val phase = nowMs / 260f
        var x = pad
        val end = max(pad, px - 6.dp.toPx())
        var first = true
        while (x <= end) {
            val y = mid + sin((x / 26.dp.toPx()) * 2 * PI.toFloat() - phase) * A
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            x += 2.dp.toPx()
        }
        if (!first) drawPath(path, pal.primary, style = Stroke(lw, cap = StrokeCap.Round))
        drawRoundRect(pal.primary, Offset(px - 3.dp.toPx(), mid - 13.dp.toPx()), Size(6.dp.toPx(), 26.dp.toPx()), CornerRadius(3.dp.toPx()))
    }
}

@Composable
private fun Ctl(icon: String, onClick: () -> Unit) {
    val pal = LocalPal.current
    Morph(Modifier.size(56.dp), pal.surfaceHighest, 28.dp, 18.dp, pressScale = 0.88f, onClick = onClick) { Ico(icon, pal.onSurface, 24.dp) }
}

@Composable
private fun Toggle(icon: String, on: Boolean, onClick: () -> Unit) {
    val pal = LocalPal.current
    Morph(Modifier.size(48.dp), if (on) pal.secondaryContainer else Color.Transparent, if (on) 14.dp else 24.dp, if (on) 24.dp else 14.dp, pressScale = 0.88f, onClick = onClick) {
        Ico(icon, if (on) pal.onSecondaryContainer else pal.onSurfaceVar, 24.dp)
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val pal = LocalPal.current
    val w by androidx.compose.animation.core.animateDpAsState(if (playing) 76.dp else 84.dp, springy(), label = "pw")
    Morph(Modifier.size(w, 72.dp), pal.primary, if (playing) 22.dp else 36.dp, if (playing) 30.dp else 22.dp, pressScale = 0.9f, onClick = onClick) {
        Ico(if (playing) "pause" else "play", pal.onPrimary, 34.dp)
    }
}

// ============================================================ compact mini player
@Composable
fun MiniPlayer(vm: AppVm, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val frame = LocalFrame.current
    val t = vm.current ?: return
    val coverAmp by animateFloatAsState(if (vm.playing) 0.06f else 0f, tween(800), label = "cookie")
    Morph(modifier.fillMaxWidth().height(72.dp), pal.surfaceMid, 28.dp, 24.dp, pressScale = 0.985f, align = Alignment.CenterStart, onClick = onOpen) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).graphicsLayer { shape = CookieShape(coverAmp); clip = true }.background(pal.primaryContainer), contentAlignment = Alignment.Center) {
                val cv = vm.cover
                if (cv != null) Image(cv, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Ico("music", pal.onPrimaryContainer, 24.dp)
            }
            Column(Modifier.weight(1f)) { T(t.title, pal.onSurface, 15f, 650); T(t.artist, pal.onSurfaceVar, 13f, 500) }
            Morph(Modifier.size(44.dp), pal.surfaceHighest, 22.dp, 14.dp, pressScale = 0.88f, onClick = { vm.prev() }) { Ico("prev", pal.onSurface, 22.dp) }
            Morph(Modifier.size(52.dp, 48.dp), pal.primary, if (vm.playing) 16.dp else 24.dp, 14.dp, pressScale = 0.9f, onClick = { vm.toggle(vm.currentShown) }) { Ico(if (vm.playing) "pause" else "play", pal.onPrimary, 26.dp) }
            Morph(Modifier.size(44.dp), pal.surfaceHighest, 22.dp, 14.dp, pressScale = 0.88f, onClick = { vm.next() }) { Ico("next", pal.onSurface, 22.dp) }
        }
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            val p = vm.durationSec().let { d -> if (d > 0) (vm.positionMs() / 1000.0 / d).toFloat().coerceIn(0f, 1f) else 0f }.also { frame.nanos }
            drawRoundRect(pal.primary, Offset(28.dp.toPx(), size.height - 9.dp.toPx()), Size((size.width - 56.dp.toPx()) * p, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
        }
    }
}
