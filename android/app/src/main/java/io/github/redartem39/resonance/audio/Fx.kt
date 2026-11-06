package io.github.redartem39.resonance.audio

import io.github.redartem39.resonance.data.Store
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

val EQ_FREQS = intArrayOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
val EQ_LABELS = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
const val CUSTOM_PRESET = "Свой"
val EQ_PRESETS: Map<String, FloatArray> = linkedMapOf(
    "Плоский" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    "Бас" to floatArrayOf(5f, 7f, 5f, 2f, 1f, 0f, 0f, 0f, 1f, 2f),
    "Высокие" to floatArrayOf(-2f, -1f, 0f, 1f, 2f, 3f, 4f, 5f, 6f, 6f),
    "Вокал" to floatArrayOf(-2f, -1f, 1f, 3f, 5f, 5f, 4f, 2f, 1f, 0f),
    "Поп" to floatArrayOf(2f, 4f, 2f, 0f, 1f, 2f, 4f, 2f, 1f, 2f),
    "Рок" to floatArrayOf(5f, 4f, 2f, -1f, -2f, -1f, 2f, 4f, 5f, 6f),
    "Джаз" to floatArrayOf(3f, 3f, 1f, 1f, 1f, 1f, 2f, 1f, 2f, 3f),
    "Классика" to floatArrayOf(0f, 1f, 2f, 2f, 2f, 2f, 1f, 2f, 3f, 4f),
)

class Dyn(val threshold: Float, val knee: Float, val ratio: Float, val attack: Float, val release: Float, val makeup: Float)
val DYN = mapOf(
    "off" to Dyn(0f, 0f, 1f, 0.003f, 0.25f, 1f),
    "balanced" to Dyn(-26f, 10f, 2.8f, 0.014f, 0.2f, 1.26f),
    "night" to Dyn(-36f, 14f, 6f, 0.006f, 0.32f, 1.6f),
)

/** Everything the "Звук" view edits. Immutable snapshot: the UI swaps it, the audio thread reads it. */
class FxParams(
    val bands: FloatArray = FloatArray(10), val preset: String = "Плоский", val pre: Float = 0f,
    val balance: Float = 0f, val mono: Boolean = false, val swap: Boolean = false, val dyn: String = "off",
) {
    fun copy(bands: FloatArray = this.bands, preset: String = this.preset, pre: Float = this.pre, balance: Float = this.balance,
             mono: Boolean = this.mono, swap: Boolean = this.swap, dyn: String = this.dyn) = FxParams(bands, preset, pre, balance, mono, swap, dyn)
}

object Fx {
    @Volatile var params = FxParams()
    @Volatile var testing = false

    fun load() {
        val b = Store.floats("eq.bands", 10)
        params = FxParams(b, Store.string("eq.preset", "Плоский"), Store.float("eq.pre", 0f), Store.float("eq.balance", 0f),
            Store.bool("eq.mono", false), Store.bool("eq.swap", false), Store.string("eq.dyn", "off"))
    }
    fun update(p: FxParams) {
        params = p
        Store.putFloats("eq.bands", p.bands); Store.put("eq.preset", p.preset); Store.put("eq.pre", p.pre); Store.put("eq.balance", p.balance)
        Store.put("eq.mono", p.mono); Store.put("eq.swap", p.swap); Store.put("eq.dyn", p.dyn)
    }
    fun resetAudio() = update(params.copy(balance = 0f, mono = false, swap = false, dyn = "off"))
}

// ------------------------------------------------------------------ DSP
private class Biquad {
    var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
    var z1l = 0.0; var z2l = 0.0; var z1r = 0.0; var z2r = 0.0
    var unity = true

    /** type 0 lowshelf, 1 peaking, 2 highshelf; same formulas the Web Audio spec uses (Q is ignored by shelves). */
    fun set(type: Int, f: Double, q: Double, gainDb: Double, fs: Double) {
        if (kotlin.math.abs(gainDb) < 0.005) { unity = true; b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0; return }
        unity = false
        val a = 10.0.pow(gainDb / 40)
        val w0 = 2 * PI * (f / fs).coerceAtMost(0.49)
        val c = cos(w0); val s = sin(w0)
        val nb0: Double; val nb1: Double; val nb2: Double; val na0: Double; val na1: Double; val na2: Double
        when (type) {
            1 -> {
                val al = s / (2 * q)
                nb0 = 1 + al * a; nb1 = -2 * c; nb2 = 1 - al * a; na0 = 1 + al / a; na1 = -2 * c; na2 = 1 - al / a
            }
            0 -> {
                val al = s / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * al
                nb0 = a * ((a + 1) - (a - 1) * c + sa); nb1 = 2 * a * ((a - 1) - (a + 1) * c); nb2 = a * ((a + 1) - (a - 1) * c - sa)
                na0 = (a + 1) + (a - 1) * c + sa; na1 = -2 * ((a - 1) + (a + 1) * c); na2 = (a + 1) + (a - 1) * c - sa
            }
            else -> {
                val al = s / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * al
                nb0 = a * ((a + 1) + (a - 1) * c + sa); nb1 = -2 * a * ((a - 1) + (a + 1) * c); nb2 = a * ((a + 1) + (a - 1) * c - sa)
                na0 = (a + 1) - (a - 1) * c + sa; na1 = 2 * ((a - 1) - (a + 1) * c); na2 = (a + 1) - (a - 1) * c - sa
            }
        }
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0
    }
    fun reset() { z1l = 0.0; z2l = 0.0; z1r = 0.0; z2r = 0.0 }
}

/** Feed-forward soft-knee compressor with linked stereo detection. */
private class Comp(val fs: Int) {
    private var gr = 0.0 // current gain reduction in dB (<= 0)
    fun reset() { gr = 0.0 }
    /** Returns the linear gain to apply for this sample pair. */
    fun gain(peak: Float, thr: Float, knee: Float, ratio: Float, aAtt: Double, aRel: Double): Float {
        val lvl = 20 * log10(maxOf(peak, 1e-6f).toDouble())
        val over = lvl - thr
        val target = when {
            over * 2 < -knee -> 0.0
            knee > 0 && over * 2 <= knee -> (1.0 / ratio - 1) * (over + knee / 2).let { it * it } / (2 * knee)
            else -> (1.0 / ratio - 1) * over
        }
        gr = if (target < gr) target + (gr - target) * aAtt else target + (gr - target) * aRel
        return 10.0.pow(gr / 20).toFloat()
    }
}

/** The whole chain: preamp -> 10-band EQ -> channel matrix (mono / swap) -> balance -> dynamics -> makeup -> limiter. Interleaved stereo float, in place. */
class FxChain(private val fs: Int) {
    private val bq = Array(10) { Biquad() }
    private val gDb = FloatArray(10)
    private val applied = FloatArray(10) { Float.NaN }
    private var pre = 1f
    private var pan = 0f
    private var make = 1f
    private val comp = Comp(fs)
    private val lim = Comp(fs)
    private var first = true

    fun reset() { bq.forEach { it.reset() }; comp.reset(); lim.reset() }

    private fun coef(sec: Float, sr: Int) = exp(-1.0 / (sr * sec.coerceAtLeast(1e-4f)))

    fun process(buf: FloatArray, frames: Int) {
        val p = Fx.params
        val dt = frames.toFloat() / fs
        val k = if (first) 1f else 1 - exp(-dt / 0.02f)
        for (i in 0 until 10) {
            gDb[i] += (p.bands[i] - gDb[i]) * k
            if (kotlin.math.abs(gDb[i] - p.bands[i]) < 0.004f) gDb[i] = p.bands[i]
            if (gDb[i] != applied[i]) {
                bq[i].set(if (i == 0) 0 else if (i == 9) 2 else 1, EQ_FREQS[i].toDouble(), 1.1, gDb[i].toDouble(), fs.toDouble())
                applied[i] = gDb[i]
            }
        }
        val preT = 10f.pow(p.pre / 20); val dynp = DYN[p.dyn] ?: DYN.getValue("off")
        pre += (preT - pre) * k; pan += (p.balance - pan) * k; make += (dynp.makeup - make) * (if (first) 1f else 1 - exp(-dt / 0.05f))
        first = false

        // matrix: mono mixes both channels into both; swap exchanges them
        val ll: Float; val lr: Float; val rl: Float; val rr: Float
        if (p.mono) { ll = 0.5f; lr = 0.5f; rl = 0.5f; rr = 0.5f } else if (p.swap) { ll = 0f; lr = 1f; rl = 1f; rr = 0f } else { ll = 1f; lr = 0f; rl = 0f; rr = 1f }
        // equal-power stereo panner (Web Audio StereoPannerNode, stereo input)
        val gl: Float; val gr2: Float; val cross: Float
        val pn = pan.coerceIn(-1f, 1f)
        val leftSide = pn <= 0f
        val x = if (leftSide) pn + 1 else pn
        val cx = cos(x * PI / 2).toFloat(); val sx = sin(x * PI / 2).toFloat()
        if (leftSide) { gl = 1f; gr2 = sx; cross = cx } else { gl = cx; gr2 = 1f; cross = sx }

        val useComp = dynp.ratio > 1f
        val cAtt = coef(dynp.attack, fs); val cRel = coef(dynp.release, fs)
        val lAtt = coef(0.005f, fs); val lRel = coef(0.05f, fs)
        val active = bq.filter { !it.unity }

        var i = 0
        while (i < frames) {
            var l = buf[2 * i] * pre; var r = buf[2 * i + 1] * pre
            if (active.isNotEmpty()) {
                var dl = l.toDouble(); var dr = r.toDouble()
                for (b in active) {
                    val yl = b.b0 * dl + b.z1l; b.z1l = b.b1 * dl - b.a1 * yl + b.z2l; b.z2l = b.b2 * dl - b.a2 * yl; dl = yl
                    val yr = b.b0 * dr + b.z1r; b.z1r = b.b1 * dr - b.a1 * yr + b.z2r; b.z2r = b.b2 * dr - b.a2 * yr; dr = yr
                }
                l = dl.toFloat(); r = dr.toFloat()
            }
            val ml = l * ll + r * rl; val mr = l * lr + r * rr
            // panner
            val pl: Float; val pr: Float
            if (leftSide) { pl = ml + mr * cross; pr = mr * gr2 } else { pl = ml * gl; pr = mr + ml * cross }
            var ol = pl; var or = pr
            if (useComp) {
                val g = comp.gain(maxOf(kotlin.math.abs(ol), kotlin.math.abs(or)), dynp.threshold, dynp.knee, dynp.ratio, cAtt, cRel)
                ol *= g * make; or *= g * make
            } else if (make != 1f) { ol *= make; or *= make }
            val g2 = lim.gain(maxOf(kotlin.math.abs(ol), kotlin.math.abs(or)), -1f, 0f, 20f, lAtt, lRel)
            buf[2 * i] = (ol * g2).coerceIn(-1f, 1f); buf[2 * i + 1] = (or * g2).coerceIn(-1f, 1f)
            i++
        }
    }
}
