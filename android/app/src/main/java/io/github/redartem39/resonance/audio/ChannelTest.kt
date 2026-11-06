package io.github.redartem39.resonance.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Channel test: a tone in the left speaker, then the right, then both (660 / 880 / 770 Hz like the desktop app).
 * It runs through its own copy of the whole sound chain on purpose, so it also shows what Balance / Swap / Mono do.
 */
object ChannelTest {
    private const val FS = 44100

    /** Plays the test; [onStep] gets "L", "R", "C" and finally null. Returns immediately. */
    fun run(onStep: (String?) -> Unit) {
        if (Fx.testing) return
        Fx.testing = true
        Thread {
            var track: AudioTrack? = null
            try {
                val total = (2.95 * FS).toInt()
                val buf = FloatArray(total * 2)
                fun beep(freq: Double, start: Double, dur: Double, pan: Double) {
                    val x = (pan + 1) / 2
                    val gl = cos(x * PI / 2).toFloat(); val gr = sin(x * PI / 2).toFloat()
                    val a = (start * FS).toInt(); val n = (dur * FS).toInt(); val ramp = (0.015 * FS).toInt()
                    for (i in 0 until n) {
                        val env = when { i < ramp -> i / ramp.toFloat(); i > n - ramp -> (n - i) / ramp.toFloat(); else -> 1f } * 0.27f
                        val s = sin(2 * PI * freq * i / FS).toFloat() * env
                        buf[2 * (a + i)] += s * gl; buf[2 * (a + i) + 1] += s * gr
                    }
                }
                beep(660.0, 0.0, 0.75, -1.0); beep(880.0, 0.93, 0.75, 1.0); beep(770.0, 1.86, 1.0, 0.0)
                FxChain(FS).process(buf, total) // the same chain the music goes through
                val pcm = ShortArray(buf.size) { (buf[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(FS).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                track.write(pcm, 0, pcm.size)
                track.play()
                val t0 = System.currentTimeMillis()
                for ((step, ms) in listOf("L" to 0L, "R" to 930L, "C" to 1860L, null to 2900L)) {
                    val wait = ms - (System.currentTimeMillis() - t0)
                    if (wait > 0) Thread.sleep(wait)
                    onStep(step)
                }
                Thread.sleep(150)
            } catch (_: Exception) {
                onStep(null)
            } finally {
                runCatching { track?.stop(); track?.release() }
                Fx.testing = false
            }
        }.start()
    }
}
