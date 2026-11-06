package io.github.redartem39.resonance.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Plugs [FxChain] into ExoPlayer's audio pipeline. Takes 16-bit or float PCM (mono or stereo), always hands on 16-bit stereo. */
@UnstableApi
class FxProcessor : BaseAudioProcessor() {
    private var chain: FxChain? = null
    private var scratch = FloatArray(0)
    private var float = false
    private var inCh = 2

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val enc = inputAudioFormat.encoding
        if ((enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount !in 1..2) return AudioFormat.NOT_SET // stay out of the way
        float = enc == C.ENCODING_PCM_FLOAT
        inCh = inputAudioFormat.channelCount
        chain = FxChain(inputAudioFormat.sampleRate)
        Spectrum.sampleRate = inputAudioFormat.sampleRate
        return AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val ch = chain ?: return
        inputBuffer.order(ByteOrder.nativeOrder())
        val bytesPerSample = if (float) 4 else 2
        val frames = inputBuffer.remaining() / (bytesPerSample * inCh)
        if (frames == 0) { inputBuffer.position(inputBuffer.limit()); return }
        if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
        var p = inputBuffer.position()
        for (i in 0 until frames) {
            val l: Float; val r: Float
            if (float) {
                l = inputBuffer.getFloat(p); p += 4
                r = if (inCh == 2) { val v = inputBuffer.getFloat(p); p += 4; v } else l
            } else {
                l = inputBuffer.getShort(p) / 32768f; p += 2
                r = if (inCh == 2) { val v = inputBuffer.getShort(p) / 32768f; p += 2; v } else l
            }
            scratch[2 * i] = l; scratch[2 * i + 1] = r
        }
        inputBuffer.position(inputBuffer.limit())
        ch.process(scratch, frames)
        Spectrum.write(scratch, frames)
        val out = replaceOutputBuffer(frames * 4)
        for (i in 0 until frames * 2) out.putShort((scratch[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        out.flip()
    }

    override fun onFlush() { chain?.reset() }
    override fun onReset() { chain = null }
}
