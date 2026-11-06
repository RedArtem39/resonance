package io.github.redartem39.resonance.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The counterpart of the Web Audio AnalyserNode: the audio processor drops the post-EQ mix here and the UI asks for
 * magnitude bytes (0..255 over -92..-6 dB, fftSize 2048, smoothing 0.8), so the visualizer and the EQ glow can reuse the desktop maths.
 */
object Spectrum {
    private const val N = 2048
    const val BINS = N / 2
    private const val MIN_DB = -92.0
    private const val MAX_DB = -6.0
    private const val SMOOTH = 0.8f

    private val ring = FloatArray(N)
    @Volatile private var wp = 0
    @Volatile private var lastWrite = 0L
    @Volatile var sampleRate = 44100

    val bytes = IntArray(BINS) // 0..255
    private val prev = FloatArray(BINS)
    private val win = FloatArray(N) { i -> val a = 0.16; (0.5 * (1 - a) - 0.5 * cos(2 * PI * i / N) + 0.5 * a * cos(4 * PI * i / N)).toFloat() }
    private val re = FloatArray(N); private val im = FloatArray(N)
    private val cosT = FloatArray(N / 2) { cos(2 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { -sin(2 * PI * it / N).toFloat() }

    val binHz: Float get() = sampleRate.toFloat() / N

    fun write(buf: FloatArray, frames: Int) {
        var w = wp
        for (i in 0 until frames) { ring[w] = 0.5f * (buf[2 * i] + buf[2 * i + 1]); w = (w + 1) and (N - 1) }
        wp = w
        lastWrite = System.nanoTime()
    }

    private fun fft() {
        var j = 0
        for (i in 0 until N - 1) {
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
            var m = N shr 1
            while (m in 1..j) { j -= m; m = m shr 1 }
            j += m
        }
        var len = 2
        while (len <= N) {
            val half = len shr 1; val step = N / len
            var i = 0
            while (i < N) {
                var k = 0
                for (jj in i until i + half) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val xr = re[jj + half] * wr - im[jj + half] * wi
                    val xi = re[jj + half] * wi + im[jj + half] * wr
                    re[jj + half] = re[jj] - xr; im[jj + half] = im[jj] - xi
                    re[jj] += xr; im[jj] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Call once per UI frame while something displays the spectrum. */
    private var lastFrame = -1L
    fun update(frame: Long) {
        if (frame == lastFrame) return
        lastFrame = frame
        val silent = System.nanoTime() - lastWrite > 150_000_000L // paused / stopped: let it decay to nothing
        val w = wp
        for (i in 0 until N) {
            re[i] = if (silent) 0f else ring[(w + i) and (N - 1)] * win[i]
            im[i] = 0f
        }
        fft()
        for (k in 0 until BINS) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / N
            prev[k] = SMOOTH * prev[k] + (1 - SMOOTH) * mag
            val db = 20 * log10(maxOf(prev[k], 1e-9f).toDouble())
            bytes[k] = (255 * (db - MIN_DB) / (MAX_DB - MIN_DB)).toInt().coerceIn(0, 255)
        }
    }
}
