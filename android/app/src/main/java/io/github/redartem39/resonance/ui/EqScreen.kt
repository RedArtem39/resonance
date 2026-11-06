package io.github.redartem39.resonance.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import io.github.redartem39.resonance.AppVm
import io.github.redartem39.resonance.audio.CUSTOM_PRESET
import io.github.redartem39.resonance.audio.EQ_FREQS
import io.github.redartem39.resonance.audio.EQ_LABELS
import io.github.redartem39.resonance.audio.EQ_PRESETS
import io.github.redartem39.resonance.audio.Spectrum
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val RANGE = 12f
private const val TRACK_H = 240f
private fun posOf(v: Float) = (RANGE - v) / (RANGE * 2)
private fun fmtDb(v: Float): String { val r = (v * 10).roundToInt() / 10f; return (if (r > 0) "+" else "") + (if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()) }
private fun easeOut(t: Float) = 1 - (1 - t.coerceIn(0f, 1f)).pow(3)

private fun balanceLabel(v: Float): String {
    val a = abs(v)
    return when {
        a >= 0.995f -> if (v < 0) "Только слева" else "Только справа"
        a < 0.005f -> "По центру"
        a < 0.25f -> if (v < 0) "Чуть левее" else "Чуть правее"
        else -> if (v < 0) "Левее" else "Правее"
    }
}
private val DYN_HINT = mapOf(
    "off" to "Без сжатия динамики: звук как в записи.",
    "balanced" to "Мягко выравнивает громкость, пики остаются живыми.",
    "night" to "Для тихого вечера: сильно сжимает пики и подтягивает тихое.",
)

/** The lightning: when a preset is applied or a slider released, a bolt runs through the handles from the lowest band to the highest. */
private class Zap {
    var active = false; var start = 0f
    val hitAt = FloatArray(10); val hitDone = BooleanArray(10)
    var rng = Array(81) { floatArrayOf(0f, 0f) }; var rngT = 0f
    class Fork(val at: Float, val ang: Float, val len: Float, val on: Boolean)
    var forks: List<List<Fork>> = emptyList()
    val lvl = FloatArray(10)
    fun go(nowMs: Float) {
        active = true; start = nowMs; hitDone.fill(false); hitAt.fill(0f)
        forks = List(9) { List(2) { Fork(0.2f + Random.nextFloat() * 0.6f, (if (Random.nextBoolean()) -1 else 1) * (0.7f + Random.nextFloat() * 0.8f), 26f + Random.nextFloat() * 40f, Random.nextFloat() < 0.65f) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqScreen(vm: AppVm) {
    val pal = LocalPal.current
    val fx = vm.fx
    var zapKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(520); zapKey++ } // a first bolt as the bands finish appearing
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(modifier = Modifier) {
            EqStage(vm, zapKey) { zapKey++ }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (name in EQ_PRESETS.keys + (if (fx.preset == CUSTOM_PRESET) listOf(CUSTOM_PRESET) else emptyList())) {
                    val on = fx.preset == name
                    Morph(Modifier.height(40.dp), if (on) pal.primary else pal.surfaceHigh, if (on) 20.dp else 12.dp, 20.dp, pressScale = 0.94f,
                        onClick = { if (name != CUSTOM_PRESET) { vm.fxPreset(name); zapKey++ } }) {
                        T(name, if (on) pal.onPrimary else pal.onSurfaceVar, 14f, 650, Modifier.padding(horizontal = 14.dp))
                    }
                }
            }
        }
        Panel("Баланс") { BalanceCard(vm) }
        Panel("Каналы") {
            SwitchRow("Моно", "Смешать оба канала в один", fx.mono) { vm.fxSet(fx.copy(mono = it, swap = if (it) false else fx.swap)) }
            SwitchRow("Поменять каналы", "Левый ↔ правый", fx.swap) { vm.fxSet(fx.copy(swap = it, mono = if (it) false else fx.mono)) }
        }
        Panel("Динамический диапазон") {
            val keys = listOf("off", "balanced", "night")
            Seg(listOf("Выкл", "Баланс", "Ночь"), keys.indexOf(fx.dyn)) { vm.fxSet(fx.copy(dyn = keys[it])) }
            T(DYN_HINT[fx.dyn] ?: "", pal.onSurfaceVar, 13f, 500, maxLines = 4, lineHeight = 19f)
        }
        Panel("Уровень") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                T("Предусиление", pal.onSurface, 15f, 600)
                M3Slider(fx.pre, { vm.fxSet(fx.copy(pre = (it * 2).roundToInt() / 2f)) }, Modifier.weight(1f), -12f, 12f, haloColor = pal.surfaceMid)
                T("${fmtDb(fx.pre)} дБ", pal.onSurfaceVar, 13f, 650, Modifier.size(width = 62.dp, height = 20.dp), align = androidx.compose.ui.text.style.TextAlign.End)
            }
            Tonal(onClick = { vm.channelTest() }) { fg ->
                ChBadge("L", vm.testStep == "L" || vm.testStep == "C", fg)
                T("Проверить каналы", fg, 15f, 650)
                ChBadge("R", vm.testStep == "R" || vm.testStep == "C", fg)
            }
            Tonal(onClick = { vm.fxReset() }) { fg -> T("Сбросить звук", fg, 15f, 650) }
        }
    }
}

@Composable
private fun ChBadge(label: String, on: Boolean, fg: Color) {
    val pal = LocalPal.current
    val sc by animateFloatAsState(if (on) 1.2f else 1f, springy(), label = "ch")
    Box(Modifier.size(28.dp).graphicsLayerScale(sc).clip(CircleShape).background(if (on) pal.primary else fg.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        T(label, if (on) pal.onPrimary else fg, 12f, 800)
    }
}
private fun Modifier.graphicsLayerScale(s: Float): Modifier = this.graphicsLayer { scaleX = s; scaleY = s }

@Composable
private fun BalanceCard(vm: AppVm) {
    val pal = LocalPal.current
    val fx = vm.fx
    Column(Modifier.fillMaxWidth().graphicsLayerAlpha(if (fx.mono) 0.4f else 1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { T(if (fx.mono) "Моно: баланс не действует" else balanceLabel(fx.balance), pal.primary, 13.5f, 700) }
        Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 6.dp)) {
                for (i in 0 until 9) {
                    val x = size.width * i / 8f
                    val mid = i == 4
                    val h = if (mid) 34.dp.toPx() else if (i % 2 == 0) 26.dp.toPx() else 18.dp.toPx()
                    val w = if (mid) 3.dp.toPx() else 2.dp.toPx()
                    drawRoundRect(pal.onSurfaceVar.copy(alpha = if (mid) 0.7f else 0.32f), Offset(x - w / 2, size.height / 2 - h / 2), Size(w, h), CornerRadius(1.dp.toPx()))
                }
            }
            M3Slider(fx.balance, { vm.fxSet(fx.copy(balance = if (abs(it) < 0.03f) 0f else it)) }, Modifier.combinedClickableDouble { vm.fxSet(fx.copy(balance = 0f)) }, -1f, 1f, fromCenter = true, haloColor = pal.surfaceMid)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T("Л  ${if (fx.balance <= 0) 100 else ((1 - fx.balance) * 100).roundToInt()}%", pal.onSurfaceVar, 12.5f, 700)
            T("${if (fx.balance >= 0) 100 else ((1 + fx.balance) * 100).roundToInt()}%  П", pal.onSurfaceVar, 12.5f, 700)
        }
    }
}

private fun Modifier.graphicsLayerAlpha(a: Float): Modifier = this.alpha(a)
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableDouble(onDouble: () -> Unit): Modifier = this.pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDouble() }) }

// ============================================================ the glass-tube equalizer stage
@Composable
private fun EqStage(vm: AppVm, zapKey: Int, requestZap: () -> Unit) {
    val pal = LocalPal.current
    val frame = LocalFrame.current
    val measurer = rememberTextMeasurer()
    val zap = remember { Zap() }
    val drag = remember { BooleanArray(10) }
    val lastTap = remember { LongArray(10) }
    LaunchedEffect(zapKey) { if (zapKey > 0 && vm.settings.fx) zap.go(System.nanoTime() / 1_000_000f) }
    val stageH = 14f + 22f + 10f + TRACK_H + 10f + 18f + 10f

    Canvas(
        Modifier.fillMaxWidth().height(stageH.dp).clip(RoundedCornerShape(26.dp))
            .background(Brush.radialGradient(listOf(pal.stageTop, pal.stageBottom), Offset.Unspecified, 900f))
            .pointerInput(Unit) {
                val d = density
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val padL = 10f * d; val cw = (size.width - 2 * padL) / 10f
                    val i = ((down.position.x - padL) / cw).toInt().coerceIn(0, 9)
                    val top = (14f + 22f + 10f) * d; val th = TRACK_H * d
                    fun setAt(y: Float): Boolean {
                        val v = (((RANGE - ((y - top) / th) * RANGE * 2) * 2).roundToInt() / 2f).coerceIn(-RANGE, RANGE)
                        if (v != vm.fx.bands[i]) { vm.fxBand(i, v); return true }
                        return false
                    }
                    var moved = setAt(down.position.y); drag[i] = true; down.consume()
                    var travelled = 0f
                    drag(down.id) { travelled += abs(it.positionChange().y); if (setAt(it.position.y)) moved = true; it.consume() }
                    drag[i] = false
                    val now = System.currentTimeMillis()
                    if (travelled < 6f * d && now - lastTap[i] < 320) { vm.fxBand(i, 0f); requestZap(); lastTap[i] = 0 }
                    else { lastTap[i] = now; if (moved) requestZap() }
                }
            },
    ) {
        val nowMs = frame.nanos / 1_000_000f
        Spectrum.update(frame.nanos)
        updateLevels(zap)
        drawStage(vm, pal, measurer, zap, drag, nowMs)
    }
}

private fun updateLevels(z: Zap) {
    val hz = Spectrum.binHz
    val b = Spectrum.bytes
    for (i in 0 until 10) {
        val f = EQ_FREQS[i]
        val lo = max(1, Math.floor(f / 1.41 / hz).toInt()); val hi = max(lo + 1, Math.ceil(f * 1.41 / hz).toInt())
        var sum = 0
        for (k in lo until hi) sum += b.getOrElse(k) { 0 }
        val v = min(1f, (sum / (hi - lo).toFloat() / 255f).pow(1.6f) * (1 + i * 0.12f) * 1.5f)
        z.lvl[i] += (v - z.lvl[i]) * (if (v > z.lvl[i]) 0.5f else 0.1f)
    }
}

private fun DrawScope.drawStage(vm: AppVm, pal: Pal, tm: TextMeasurer, z: Zap, drag: BooleanArray, nowMs: Float) {
    val d = density
    val padL = 10f * d; val cw = (size.width - 2 * padL) / 10f
    val trackTop = (14f + 22f + 10f) * d; val th = TRACK_H * d
    val bands = vm.fx.bands
    val cx = { i: Int -> padL + (i + 0.5f) * cw }
    val yOf = { v: Float -> trackTop + posOf(v) * th }

    // dB grid: 0 dB is the reference line, ±6 / ±12 are faint
    for (db in intArrayOf(-12, -6, 0, 6, 12)) {
        val y = trackTop + th / 2 - (db / (RANGE * 2)) * th
        drawLine(pal.stageOnVar.copy(alpha = if (db == 0) 0.42f else 0.16f), Offset(padL, y), Offset(size.width - padL, y), 1f * d,
            pathEffect = if (db == 0) null else PathEffect.dashPathEffect(floatArrayOf(3f * d, 6f * d)))
    }

    val hit = FloatArray(10) { if (z.hitAt[it] > 0) (nowMs - z.hitAt[it]) / 1000f else 99f }
    for (i in 0 until 10) {
        val x = cx(i); val bc = bandColor(pal, i); val v = bands[i]; val lvl = z.lvl[i]; val dragging = drag[i]
        // value pill
        val pillW = min(40f * d, cw - 3f * d); val pillH = 22f * d
        val lift = if (dragging) -4f * d else 0f
        withTransform({ translate(0f, lift); scale(if (dragging) 1.16f else 1f, if (dragging) 1.16f else 1f, Offset(x, 14f * d + pillH / 2)) }) {
            drawRoundRect(bc.copy(alpha = if (dragging) 0.30f else 0.16f), Offset(x - pillW / 2, 14f * d), Size(pillW, pillH), CornerRadius(pillH / 2))
            val txt = tm.measure(fmtDb(v), TextStyle(fontSize = 12.sp, fontWeight = FontWeight(750), color = lerp(pal.stageOn, bc, 0.62f).copy(alpha = if (v == 0f) 0.5f else 1f)))
            drawText(txt, topLeft = Offset(x - txt.size.width / 2f, 14f * d + (pillH - txt.size.height) / 2))
        }
        // tube
        val tw = 14f * d
        val tubeFlash = (1 - hit[i] / 0.9f).coerceIn(0f, 1f)
        if (tubeFlash > 0) drawRoundRect(bc.copy(alpha = 0.55f * tubeFlash), Offset(x - tw / 2 - 5 * d, trackTop - 5 * d), Size(tw + 10 * d, th + 10 * d), CornerRadius(tw))
        drawRoundRect(Brush.horizontalGradient(listOf(pal.stageOn.copy(alpha = 0.11f), pal.stageOn.copy(alpha = 0.03f), Color.Black.copy(alpha = 0.16f)), x - tw / 2, x + tw / 2), Offset(x - tw / 2, trackTop), Size(tw, th), CornerRadius(tw / 2))
        drawRoundRect(pal.stageOn.copy(alpha = 0.09f), Offset(x - tw / 2, trackTop), Size(tw, th), CornerRadius(tw / 2), style = Stroke(1f * d))
        drawLine(pal.stageOnVar.copy(alpha = 0.32f), Offset(x - 8 * d, trackTop + th / 2), Offset(x + 8 * d, trackTop + th / 2), 2f * d, StrokeCap.Round)
        // fill: from the handle to the bottom of the tube, glowing with the band's live level
        val fy = yOf(v)
        if (lvl > 0.02f) drawRoundRect(bc.copy(alpha = 0.35f * lvl), Offset(x - tw / 2 - 5 * d * lvl, fy - 2 * d), Size(tw + 10 * d * lvl, trackTop + th - fy + 4 * d), CornerRadius(tw))
        drawRoundRect(Brush.verticalGradient(listOf(bc.copy(alpha = 0.88f), bc.copy(alpha = 0.22f)), fy, trackTop + th), Offset(x - tw / 2, fy), Size(tw, max(0f, trackTop + th - fy)), CornerRadius(tw / 2))
        drawRoundRect(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent), x - tw / 2, x + tw * 0.05f), Offset(x - tw / 2, fy), Size(tw, max(0f, trackTop + th - fy)), CornerRadius(tw / 2))
        // orb: halo that breathes with the music, then the glass handle
        val orbScale = (if (dragging) 1.34f else 1f) * (1 + 0.6f * (1 - easeOut(hit[i] / 0.8f)).let { if (hit[i] < 0.8f) it else 0f })
        val r = 13f * d * orbScale
        val haloR = 33f * d * (0.85f + lvl * 0.55f)
        drawCircle(Brush.radialGradient(listOf(bc.copy(alpha = 0.78f * (0.3f + lvl * 0.7f)), Color.Transparent), Offset(x, fy), haloR), haloR, Offset(x, fy))
        drawCircle(Color.Black.copy(alpha = 0.35f), r + 1 * d, Offset(x, fy + 4 * d))
        drawCircle(pal.stageSurface, r + 4 * d, Offset(x, fy))
        drawCircle(
            Brush.radialGradient(0f to Color.White, 0.26f to lerp(bc, Color.White, 0.5f), 0.62f to bc, 1f to lerp(bc, Color.Black, 0.62f), center = Offset(x - r * 0.32f, fy - r * 0.44f), radius = r * 1.45f),
            r, Offset(x, fy),
        )
        if (hit[i] < 0.8f) drawCircle(Color.White.copy(alpha = 0.55f * (1 - hit[i] / 0.8f)), r, Offset(x, fy))
        // frequency label
        val lab = tm.measure(EQ_LABELS[i], TextStyle(fontSize = 12.sp, fontWeight = FontWeight(650), color = pal.stageOnVar))
        drawText(lab, topLeft = Offset(x - lab.size.width / 2f, trackTop + th + 10 * d))
    }
    drawZap(pal, z, bands, cx, yOf, trackTop, nowMs)
}

private fun DrawScope.drawZap(pal: Pal, z: Zap, bands: FloatArray, cx: (Int) -> Float, yOf: (Float) -> Float, trackTop: Float, nowMs: Float) {
    if (!z.active) return
    val d = density
    val age = (nowMs - z.start) / 1000f
    val p = min(9f, age / 0.1f)
    val fade = ((age - 0.9f - 0.25f) / 0.55f).coerceIn(0f, 1f)
    if (fade >= 1f) { z.active = false; return }
    val pts = List(10) { Offset(cx(it), yOf(bands[it])) }
    for (i in 0 until 10) if (!z.hitDone[i] && p >= i) { z.hitDone[i] = true; z.hitAt[i] = nowMs }
    if (nowMs - z.rngT > 45) { z.rngT = nowMs; z.rng = Array(81) { floatArrayOf(Random.nextFloat() * 2 - 1, Random.nextFloat() * 2 - 1) } } // re-roll the crackle ~22 times a second

    val line = ArrayList<Offset>()
    val steps = 8
    var i = 0
    while (i < 9 && p > i) {
        val frac = min(1f, p - i); val a = pts[i]; val b = pts[i + 1]
        val dx = b.x - a.x; val dy = b.y - a.y; val len = hypot(dx, dy).let { if (it == 0f) 1f else it }; val nx = -dy / len; val ny = dx / len
        var j = if (i == 0) 0 else 1
        while (j <= steps) {
            val t = j.toFloat() / steps * frac
            val env = sin(min(1f, j.toFloat() / steps) * PI.toFloat()) * (1 - fade)
            val r = z.rng[i * 9 + min(j, 8)]
            line += Offset(a.x + dx * t + nx * r[0] * 12 * d * env + (dx / len) * r[1] * 3 * d * env, a.y + dy * t + ny * r[0] * 12 * d * env + (dy / len) * r[1] * 3 * d * env)
            if (j.toFloat() / steps >= frac) break
            j++
        }
        i++
    }
    val grad = Brush.horizontalGradient(listOf(pal.stagePrimary, pal.stageTertiary), 0f, size.width)
    // pulses on the bands the bolt has reached
    for (k in 0 until 10) {
        if (z.hitAt[k] == 0f) continue
        val t = (nowMs - z.hitAt[k]) / 1000f
        if (t > 1.2f) continue
        val (x, y) = pts[k].let { it.x to it.y }
        val hr = (12 + 20 * easeOut(t / 0.5f)) * d
        drawCircle(Brush.radialGradient(listOf(pal.stagePrimary, Color.Transparent), Offset(x, y), 34 * d), 34 * d, Offset(x, y), alpha = (0.65f * (1 - (t / 0.7f).coerceIn(0f, 1f)) * (1 - fade)).coerceIn(0f, 1f))
        drawCircle(pal.stageTertiary, (8 + 22 * easeOut(t / 0.8f)) * d, Offset(x, y), alpha = (0.75f * (1 - (t / 0.8f).coerceIn(0f, 1f))).coerceIn(0f, 1f), style = Stroke(2 * d))
        val ty = y - (y - (trackTop - 20 * d)) * easeOut(t / 1.0f)
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White, Color.Transparent), ty - 26 * d, ty + 26 * d), Offset(x - 3 * d, ty - 26 * d), Size(6 * d, 52 * d), alpha = (0.55f * (1 - (t / 1.0f).coerceIn(0f, 1f))).coerceIn(0f, 1f))
        @Suppress("UNUSED_VARIABLE") val unused = hr
    }
    if (line.size > 1) {
        val path = Path().apply { line.forEachIndexed { k, q -> if (k == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) } }
        val layers = listOf(Triple(20f, 0.16f, true), Triple(10f, 0.34f, true), Triple(4.5f, 0.7f, true), Triple(2.2f, 1f, false))
        for ((lw, al, useGrad) in layers) {
            val a = al * (1 - fade)
            if (useGrad) drawPath(path, grad, alpha = a, style = Stroke(lw * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
            else drawPath(path, Color.White, alpha = a, style = Stroke(lw * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // forks: short thin branches off the bolt
        i = 0
        while (i < 9 && p > i) {
            for (f in z.forks[i]) {
                if (!f.on || p < i + f.at) { continue }
                val a = pts[i]; val b = pts[i + 1]; val base = atan2(b.y - a.y, b.x - a.x) + f.ang
                var x = a.x + (b.x - a.x) * f.at; var y = a.y + (b.y - a.y) * f.at
                val fp = Path().apply { moveTo(x, y) }
                for (k in 1..3) {
                    val r = z.rng[(i * 9 + k) % z.rng.size]
                    x += cos(base + r[0] * 0.5f) * (f.len / 3) * d; y += sin(base + r[0] * 0.5f) * (f.len / 3) * d
                    fp.lineTo(x, y)
                }
                drawPath(fp, Color.White, alpha = 0.55f * (1 - fade), style = Stroke(1.4f * d, cap = StrokeCap.Round))
            }
            i++
        }
    }
}
