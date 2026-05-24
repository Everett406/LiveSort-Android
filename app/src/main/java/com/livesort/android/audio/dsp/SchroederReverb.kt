package com.livesort.android.audio.dsp

import kotlin.math.min

/**
 * 简化版 Schroeder 混响
 *
 * 3 个并行 Comb 滤波器 + 1 个 AllPass 滤波器
 * 在性能和效果之间取得平衡，对齐原作者混响听感
 */
class SchroederReverb(private val sampleRate: Int) {

    // Comb 滤波器延迟时间（毫秒），会根据采样率自动缩放为样本数
    private val combDelaysMs = doubleArrayOf(25.3, 29.9, 33.6)
    private val combFeedback = doubleArrayOf(0.72, 0.68, 0.65)

    // AllPass 滤波器延迟时间（毫秒）
    private val allPassDelayMs = 7.6
    private val allPassFeedback = 0.5

    // 延迟缓冲区
    private val combBuffers: Array<DoubleArray>
    private val combIndices: IntArray
    private val allPassBuffer: DoubleArray
    private var allPassIndex = 0

    private var wetMix = 0.0

    init {
        combBuffers = Array(combDelaysMs.size) { i ->
            DoubleArray((combDelaysMs[i] * sampleRate / 1000.0).toInt()) { 0.0 }
        }
        combIndices = IntArray(combDelaysMs.size) { 0 }
        allPassBuffer = DoubleArray((allPassDelayMs * sampleRate / 1000.0).toInt()) { 0.0 }
    }

    fun setWetness(wetness: Double) {
        wetMix = min(0.5, wetness.coerceIn(0.0, 1.0))
    }

    fun process(input: Double): Double {
        if (wetMix <= 1e-5) return input

        // 并行 Comb 滤波器
        var combSum = 0.0
        for (i in combBuffers.indices) {
            val buf = combBuffers[i]
            val idx = combIndices[i]
            val delayed = buf[idx]
            val out = input + delayed * combFeedback[i]
            buf[idx] = out
            combIndices[i] = (idx + 1) % buf.size
            combSum += delayed
        }
        combSum /= combBuffers.size

        // AllPass 滤波器
        val apDelayed = allPassBuffer[allPassIndex]
        val apOut = combSum - allPassFeedback * apDelayed
        allPassBuffer[allPassIndex] = combSum + apFeedback * apOut
        allPassIndex = (allPassIndex + 1) % allPassBuffer.size

        // Wet/Dry 混合
        return input * (1.0 - wetMix) + apOut * wetMix
    }

    fun processBlock(input: FloatArray, output: FloatArray) {
        for (i in input.indices) {
            output[i] = process(input[i].toDouble()).toFloat()
        }
    }

    fun reset() {
        for (buf in combBuffers) {
            for (i in buf.indices) buf[i] = 0.0
        }
        for (i in combIndices.indices) combIndices[i] = 0
        for (i in allPassBuffer.indices) allPassBuffer[i] = 0.0
        allPassIndex = 0
    }

    companion object {
        private const val apFeedback = 0.5
    }
}
