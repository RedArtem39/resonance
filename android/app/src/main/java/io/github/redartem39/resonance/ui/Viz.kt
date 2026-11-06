package io.github.redartem39.resonance.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import io.github.redartem39.resonance.audio.Spectrum
import androidx.compose.ui.unit.dp
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.ceil
import kotlin.math.floor

private const val BARS = 26
private const val WAVE = 72
private fun edgesFor(n: Int) = FloatArray(n + 1) { i -> 45f * (15000f / 45f).pow(i.toFloat() / n) }

/** Port of viz.js: everything is smoothed in time (attack/release), then in space, and the auto-gain moves slowly, so nothing twitches. */
private class VizState {
    val barEdges = edgesFor(BARS); val waveEdges = edgesFor(WAVE)
    val barVal = FloatArray(BARS); val barPeak = FloatArray(BARS); val waveVal = FloatArray(WAVE)
    val rawBar = FloatArray(BARS); val rawWave = FloatArray(WAVE)
    var bass = 0f; var max = 0.5f; var last = 0L
}

private fun bandLevels(edges: FloatArray, n: Int, hz: Float, out: FloatArray) {
    val bins = Spectrum.bytes
    for (i in 0 until n) {
        val f0 = edges[i]; val f1 = edges[i + 1]; val lo = f0 / hz; val hi = f1 / hz
        val v: Float
        if (hi - lo < 1.5f) {
            val fb = (f0 + f1) / 2 / hz; val i0 = floor(fb).toInt(); val fr = fb - i0
            v = ((bins.getOrElse(i0) { 0 }) * (1 - fr) + (bins.getOrElse(i0 + 1) { 0 }) * fr) / 255f
        } else {
            var m = 0; var s = 0; var c = 0
            for (k in max(1, floor(lo).toInt()) until ceil(hi).toInt()) { val b = bins.getOrElse(k) { 0 }; m = max(m, b); s += b; c++ }
            v = (m * 0.6f + (s / (if (c == 0) 1 else c).toFloat()) * 0.4f) / 255f
        }
        out[i] = v * (0.8f + (i.toFloat() / n) * 0.6f) // spectra fall off with frequency; lift the top end a little
    }
}

private fun follow(arr: FloatArray, raw: FloatArray, dt: Float, gain: Float, up: Float, down: Float) {
    for (i in arr.indices) {
        val target = min(1f, (raw[i] / gain).pow(1.7f))
        arr[i] += (target - arr[i]) * (1 - exp(-dt / (if (target > arr[i]) up else down)))
    }
}

private fun blur(src: FloatArray, k: FloatArray): FloatArray {
    val r = (k.size - 1) / 2; val sum = k.sum()
    return FloatArray(src.size) { i ->
        var s = 0f
        for (j in -r..r) s += src[min(src.size - 1, max(0, i + j))] * k[j + r]
        s / sum
    }
}

/** Full-bleed background across the bottom of the panel. Mode: off | bars | wave. */
@Composable
fun VizLayer(mode: String, modifier: Modifier = Modifier) {
    if (mode == "off") return
    val pal = LocalPal.current
    val frame = LocalFrame.current
    val st = remember { VizState() }
    Box(
        modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.58f to Color.Black, 1f to Color.Black), blendMode = BlendMode.DstIn)
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val nowNs = frame.nanos
            Spectrum.update(nowNs)
            val now = nowNs / 1_000_000f
            val dt = if (st.last != 0L) min(0.1f, (nowNs - st.last) / 1e9f) else 0.016f
            st.last = nowNs
            val w = size.width; val h = size.height
            val hz = Spectrum.binHz
            val barMode = mode == "bars"
            val edges = if (barMode) st.barEdges else st.waveEdges
            val n = if (barMode) BARS else WAVE
            val raw = if (barMode) st.rawBar else st.rawWave
            bandLevels(edges, n, hz, raw)
            var frameMax = 0f
            for (i in 0 until n) frameMax = max(frameMax, raw[i])
            // auto-gain: quick to follow a louder passage (150 ms), very slow to relax (6 s) — no pumping
            st.max += (frameMax - st.max) * (1 - exp(-dt / (if (frameMax > st.max) 0.15f else 6f)))
            val gain = max(0.3f, st.max)
            var bass = 0f
            for (k in 1..6) bass += Spectrum.bytes[k]
            bass = bass / 6f / 255f
            st.bass += (bass - st.bass) * (1 - exp(-dt / (if (bass > st.bass) 0.06f else 0.25f)))

            val grad = Brush.horizontalGradient(listOf(pal.primary, pal.tertiary), 0f, w)
            // bass glow under the controls
            drawRect(
                Brush.radialGradient(listOf(pal.primary, Color.Transparent), Offset(w / 2, h), w * 0.75f),
                alpha = (0.05f + st.bass * st.bass * 0.45f).coerceIn(0f, 1f),
            )
            if (barMode) drawBars(st, dt, gain, raw, grad, w, h) else drawWave(st, dt, gain, raw, grad, w, h, now)
        }
    }
}

private fun DrawScope.drawBars(st: VizState, dt: Float, gain: Float, raw: FloatArray, grad: Brush, w: Float, h: Float) {
    follow(st.barVal, raw, dt, gain, 0.05f, 0.2f)
    val vals = blur(st.barVal, floatArrayOf(1f, 2f, 1f))
    val slot = w / BARS
    for (i in 0 until BARS) {
        st.barPeak[i] = max(vals[i], st.barPeak[i] - dt * 0.33f) // caps fall at a steady speed
        val v = vals[i]; val bw = slot * (0.3f + v * 0.5f); val x = slot * (i + 0.5f) - bw / 2; val bh = max(bw, v * h * 0.96f)
        drawRoundRect(grad, Offset(x, h - bh), Size(bw, bh), CornerRadius(bw / 2), alpha = 0.2f + v * 0.42f)
        if (st.barPeak[i] > v + 0.04f) {
            drawRoundRect(grad, Offset(x, h - st.barPeak[i] * h * 0.96f - bw * 0.9f), Size(bw, bw * 0.55f), CornerRadius(bw * 0.28f), alpha = 0.8f)
        }
    }
}

private fun DrawScope.drawWave(st: VizState, dt: Float, gain: Float, raw: FloatArray, grad: Brush, w: Float, h: Float, nowMs: Float) {
    follow(st.waveVal, raw, dt, gain, 0.07f, 0.28f)
    val front = blur(st.waveVal, floatArrayOf(1f, 2f, 3f, 2f, 1f))
    val sway = { i: Int -> sin(nowMs / 1100f + i * 0.35f) * 1.6f } // a very gentle drift so a held note still breathes
    fun build(close: Boolean): Path {
        val x = { i: Int -> i.toFloat() / (WAVE - 1) * w }
        val y = { i: Int -> h - 2 - min(1f, front[i]) * h * 0.92f + sway(i) }
        val p = Path()
        if (close) { p.moveTo(0f, h); p.lineTo(0f, y(0)) } else p.moveTo(0f, y(0))
        for (i in 0 until WAVE - 1) p.quadraticTo(x(i), y(i), (x(i) + x(i + 1)) / 2, (y(i) + y(i + 1)) / 2)
        p.lineTo(w, y(WAVE - 1))
        if (close) { p.lineTo(w, h); p.close() }
        return p
    }
    drawPath(build(true), grad, alpha = 0.52f)
    drawPath(build(false), grad, alpha = 0.9f, style = Stroke(2.5f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}
