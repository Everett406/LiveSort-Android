package com.livesort.android.audio.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * ExoPlayer AudioProcessor：混响效果（Schroeder 混响）
 */
class ReverbAudioProcessor : AudioProcessor {

    data class Params(val wetness: Double = 0.0)

    private val paramsRef = AtomicReference(Params())

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    private var reverbL: SchroederReverb? = null
    private var reverbR: SchroederReverb? = null
    private var channelCount = 2

    fun setParams(p: Params) {
        paramsRef.set(p)
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        inputFormat = inputAudioFormat
        outputFormat = inputAudioFormat
        channelCount = inputAudioFormat.channelCount
        val sr = inputAudioFormat.sampleRate
        reverbL = SchroederReverb(sr)
        if (channelCount >= 2) {
            reverbR = SchroederReverb(sr)
        }
        return outputFormat
    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }
        val remaining = inputBuffer.remaining()
        val params = paramsRef.get()
        val wetness = params.wetness.coerceIn(0.0, 1.0)

        reverbL?.setWetness(wetness)
        reverbR?.setWetness(wetness)

        if (outputBuffer.capacity() < remaining) {
            outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }

        val limit = inputBuffer.limit()
        val position = inputBuffer.position()

        if (wetness <= 1e-5) {
            outputBuffer.put(inputBuffer)
        } else {
            val frames = remaining / (2 * channelCount)
            var srcPos = position
            for (frame in 0 until frames) {
                // Left channel
                val sampleL = inputBuffer.getShort(srcPos).toInt()
                var floatL = sampleL / 32768.0f
                reverbL?.let { floatL = it.process(floatL.toDouble()).toFloat() }
                val outL = (floatL * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
                outputBuffer.putShort(outL)
                srcPos += 2

                if (channelCount >= 2) {
                    // Right channel
                    val sampleR = inputBuffer.getShort(srcPos).toInt()
                    var floatR = sampleR / 32768.0f
                    reverbR?.let { floatR = it.process(floatR.toDouble()).toFloat() }
                    val outR = (floatR * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
                    outputBuffer.putShort(outR)
                    srcPos += 2
                }
            }
            inputBuffer.position(srcPos)
        }

        outputBuffer.flip()
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        reverbL?.reset()
        reverbR?.reset()
    }

    override fun reset() {
        flush()
        inputFormat = AudioFormat.NOT_SET
        outputFormat = AudioFormat.NOT_SET
    }

    companion object {
        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
