package com.livesort.android.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 标准二阶 IIR Biquad 滤波器
 *
 * 基于 Audio EQ Cookbook 公式实现
 * 支持 lowShelf、highShelf、lowPass 模式
 *
 * 对齐原作者 Web Audio API BiquadFilterNode 行为
 */
class BiquadFilter(private val sampleRate: Float) {

    enum class Type {
        LOW_SHELF, HIGH_SHELF, LOW_PASS
    }

    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a0 = 1.0
    private var a1 = 0.0
    private var a2 = 0.0

    // 历史样本（用于 IIR 递归）
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    /**
     * 配置滤波器参数
     *
     * @param type 滤波器类型
     * @param frequencyHz 中心/截止频率 (Hz)
     * @param dBgain 增益 (dB)，正数为提升，负数为衰减
     * @param q Q 值（谐振/带宽）
     */
    fun configure(type: Type, frequencyHz: Double, dBgain: Double = 0.0, q: Double = 0.707) {
        val w0 = 2.0 * PI * frequencyHz / sampleRate
        val cosw0 = cos(w0)
        val sinw0 = sin(w0)
        val alpha = sinw0 / (2.0 * q)

        when (type) {
            Type.LOW_SHELF -> {
                val A = sqrt(10.0.pow(dBgain / 40.0))
                val sqrt2A = 2.0 * sqrt(A) * alpha
                b0 = A * ((A + 1) - (A - 1) * cosw0 + sqrt2A)
                b1 = 2 * A * ((A - 1) - (A + 1) * cosw0)
                b2 = A * ((A + 1) - (A - 1) * cosw0 - sqrt2A)
                a0 = (A + 1) + (A - 1) * cosw0 + sqrt2A
                a1 = -2 * ((A - 1) + (A + 1) * cosw0)
                a2 = (A + 1) + (A - 1) * cosw0 - sqrt2A
            }
            Type.HIGH_SHELF -> {
                val A = sqrt(10.0.pow(dBgain / 40.0))
                val sqrt2A = 2.0 * sqrt(A) * alpha
                b0 = A * ((A + 1) + (A - 1) * cosw0 + sqrt2A)
                b1 = -2 * A * ((A - 1) + (A + 1) * cosw0)
                b2 = A * ((A + 1) + (A - 1) * cosw0 - sqrt2A)
                a0 = (A + 1) - (A - 1) * cosw0 + sqrt2A
                a1 = 2 * ((A - 1) - (A + 1) * cosw0)
                a2 = (A + 1) - (A - 1) * cosw0 - sqrt2A
            }
            Type.LOW_PASS -> {
                b0 = (1.0 - cosw0) / 2.0
                b1 = 1.0 - cosw0
                b2 = (1.0 - cosw0) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosw0
                a2 = 1.0 - alpha
            }
        }
    }

    /**
     * 处理单样本
     */
    fun process(input: Double): Double {
        val output = (b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
        x2 = x1
        x1 = input
        y2 = y1
        y1 = output
        return output
    }

    /**
     * 处理整个缓冲区
     */
    fun processBlock(input: FloatArray, output: FloatArray) {
        for (i in input.indices) {
            output[i] = process(input[i].toDouble()).toFloat()
        }
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }
}
