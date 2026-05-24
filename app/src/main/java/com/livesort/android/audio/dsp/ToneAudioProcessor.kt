package com.livesort.android.audio.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * ExoPlayer AudioProcessor：Tone 效果（lowShelf + highShelf + lowPass）
 *
 * 对齐原作者 Web Audio API 效果链：
 * - lowShelf @ 220Hz（深度决定增益）
 * - highShelf @ 1800Hz（深度决定衰减）
 * - lowPass（深度决定截止频率和 Q 值）
 */
class ToneAudioProcessor : AudioProcessor {

    data class Params(val depth: Double = 0.0)

    private val paramsRef = AtomicReference(Params())

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputFormat: AudioFormat = AudioFormat.NOT_SET
    private var pendingOutputBuffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    private var lowShelf: BiquadFilter? = null
    private var highShelf: BiquadFilter? = null
    private var lowPass: BiquadFilter? = null
    private var sampleRate = 44100f
    private var channelCount = 2

    private val tempInput = FloatArray(1024)
    private val tempOutput = FloatArray(1024)

    fun setParams(p: Params) {
        paramsRef.set(p)
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        inputFormat = inputAudioFormat
        outputFormat = inputAudioFormat
        sampleRate = inputAudioFormat.sampleRate.toFloat()
        channelCount = inputAudioFormat.channelCount

        lowShelf = BiquadFilter(sampleRate)
        highShelf = BiquadFilter(sampleRate)
        lowPass = BiquadFilter(sampleRate)
        return outputFormat
    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }
        val remaining = inputBuffer.remaining()
        val params = paramsRef.get()
        val depth = params.depth.coerceIn(0.0, 1.0)

        // 配置滤波器
        lowShelf?.configure(
            BiquadFilter.Type.LOW_SHELF,
            frequencyHz = 220.0,
            dBgain = depth * 11.5,
            q = 0.65
        )
        highShelf?.configure(
            BiquadFilter.Type.HIGH_SHELF,
            frequencyHz = 1800.0,
            dBgain = -depth * 24.0,
            q = 0.65
        )
        val cutoff = (18000.0 - depth * 17200.0).coerceIn(800.0, 18000.0)
        val qVal = 0.65 + depth * 1.35
        lowPass?.configure(
            BiquadFilter.Type.LOW_PASS,
            frequencyHz = cutoff,
            dBgain = 0.0,
            q = qVal
        )

        val dryGain = (1.0 - depth * 0.08).toFloat()

        // 确保输出缓冲区足够大
        if (outputBuffer.capacity() < remaining) {
            outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }

        val limit = inputBuffer.limit()
        val position = inputBuffer.position()

        if (depth <= 1e-5) {
            // 无效果，直接透传
            outputBuffer.put(inputBuffer)
        } else {
            // 处理 16-bit PCM 立体声
            val frames = remaining / (2 * channelCount)
            var srcPos = position
            for (frame in 0 until frames) {
                for (ch in 0 until channelCount) {
                    val sample = inputBuffer.getShort(srcPos).toInt()
                    var floatSample = sample / 32768.0f

                    // 应用滤波器链
                    lowShelf?.let { floatSample = it.process(floatSample.toDouble()).toFloat() }
                    highShelf?.let { floatSample = it.process(floatSample.toDouble()).toFloat() }
                    lowPass?.let { floatSample = it.process(floatSample.toDouble()).toFloat() }

                    floatSample *= dryGain

                    val out = (floatSample * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
                    outputBuffer.putShort(out)
                    srcPos += 2
                }
            }
            inputBuffer.position(srcPos)
        }

        outputBuffer.flip()
        this.inputBuffer = inputBuffer
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
        lowShelf?.reset()
        highShelf?.reset()
        lowPass?.reset()
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
