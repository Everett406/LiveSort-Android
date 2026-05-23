package com.livesort.shared.audio

import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.jvm.AudioDispatcherFactory
import be.tarsos.dsp.util.fft.FFT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Desktop 音频分析器实现（基于 TarsosDSP + Java Sound）
 *
 * 算法与 Android 端保持一致，确保跨平台结果相同
 */
actual class AudioAnalyzer actual constructor() {

    private val sampleRate = 22050
    private val bufferSize = 1024
    private val overlap = 512

    actual suspend fun analyze(filePath: String): AudioFeatures? = withContext(Dispatchers.Default) {
        val file = File(filePath)
        if (!file.exists()) return@withContext null

        try {
            val durationSec = getDuration(filePath)

            val mainDuration = min(30.0, durationSec)
            val mainSamples = loadSamples(filePath, 0.0, mainDuration)

            val startDuration = min(15.0, durationSec)
            val startSamples = loadSamples(filePath, 0.0, startDuration)

            val endOffset = max(0.0, durationSec - 15.0)
            val endSamples = loadSamples(filePath, endOffset, min(15.0, durationSec))

            val tailScanOffset = max(0.0, durationSec - min(40.0, durationSec))
            val tailScanSamples = loadSamples(filePath, tailScanOffset, min(40.0, durationSec))

            val bpm = estimateBpm(mainSamples, sampleRate)
            val startBpm = estimateBpm(startSamples, sampleRate)
            val endBpm = estimateBpm(endSamples, sampleRate)

            val energy = computeRmsEnergy(mainSamples)
            val startEnergy = computeRmsEnergy(startSamples)
            val endEnergy = computeRmsEnergy(endSamples)

            val start10sEnergy = computeRmsEnergy(startSamples.take(sampleRate * 10))
            val end10sEnergy = computeRmsEnergy(endSamples.takeLast(sampleRate * 10))

            val brightness = computeSpectralCentroid(mainSamples, sampleRate)

            val invalidTailSec = estimateInvalidTailSec(tailScanSamples, sampleRate)
            val dynamicWindowSec = (10.0 + invalidTailSec * 0.55).coerceIn(8.0, 24.0)
            val dynamicWindowSamples = (sampleRate * dynamicWindowSec).toInt()
            val effectiveTailSamples = (sampleRate * invalidTailSec).toInt()

            val endDynamicSamples = if (effectiveTailSamples > 0 && tailScanSamples.size > effectiveTailSamples) {
                tailScanSamples.dropLast(effectiveTailSamples).takeLast(dynamicWindowSamples)
            } else {
                tailScanSamples.takeLast(dynamicWindowSamples)
            }
            val startDynamicSamples = startSamples.take(dynamicWindowSamples)

            val startDynamicEnergy = computeRmsEnergy(startDynamicSamples)
            val endDynamicEnergy = computeRmsEnergy(endDynamicSamples)

            val mixEntrySec = (4.0 + invalidTailSec).coerceIn(4.0, 44.0)
            val mixEffectStartSec = (10.0 + invalidTailSec).coerceIn(8.0, 50.0)

            AudioFeatures(
                durationSec = durationSec,
                bpm = bpm,
                energy = energy,
                brightness = brightness,
                startBpm = startBpm,
                startEnergy = startEnergy,
                start10sEnergy = start10sEnergy,
                startDynamicEnergy = startDynamicEnergy,
                endBpm = endBpm,
                endEnergy = endEnergy,
                end10sEnergy = end10sEnergy,
                endDynamicEnergy = endDynamicEnergy,
                mixEntrySec = mixEntrySec,
                mixEffectStartSec = mixEffectStartSec
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getDuration(filePath: String): Double {
        return try {
            val file = File(filePath)
            val audioInputStream = AudioSystem.getAudioInputStream(file)
            val format = audioInputStream.format
            val frames = audioInputStream.frameLength
            frames / format.frameRate.toDouble()
        } catch (e: Exception) {
            try {
                // Fallback with TarsosDSP pipe
                val dispatcher = AudioDispatcherFactory.fromPipe(filePath, sampleRate, bufferSize, overlap)
                var samples = 0L
                dispatcher.addAudioProcessor { audioEvent ->
                    samples += audioEvent.bufferSize.toLong()
                    true
                }
                dispatcher.run()
                samples / sampleRate.toDouble()
            } catch (e2: Exception) {
                0.0
            }
        }
    }

    private fun loadSamples(filePath: String, offsetSec: Double, durationSec: Double): List<Float> {
        val samples = mutableListOf<Float>()
        val skipSamples = (offsetSec * sampleRate).toLong()
        val maxSamples = (durationSec * sampleRate).toLong()

        try {
            val dispatcher = AudioDispatcherFactory.fromPipe(filePath, sampleRate, bufferSize, overlap)
            var processed = 0L

            dispatcher.addAudioProcessor { audioEvent ->
                if (processed >= skipSamples && processed < skipSamples + maxSamples) {
                    val buffer = audioEvent.floatBuffer
                    val toAdd = min(buffer.size.toLong(), skipSamples + maxSamples - processed).toInt()
                    for (i in 0 until toAdd) {
                        samples.add(buffer[i])
                    }
                }
                processed += audioEvent.bufferSize
                processed < skipSamples + maxSamples
            }
            dispatcher.run()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return samples
    }

    private fun estimateBpm(samples: List<Float>, sr: Int): Double {
        if (samples.size < sr * 5) return 0.0

        val envelope = computeEnvelope(samples, sr)
        val minLag = (sr * 60.0 / 210.0).toInt()
        val maxLag = (sr * 60.0 / 55.0).toInt()

        var bestLag = minLag
        var bestCorr = Double.NEGATIVE_INFINITY

        for (lag in minLag..maxLag) {
            var corr = 0.0
            for (i in envelope.indices) {
                if (i + lag < envelope.size) {
                    corr += envelope[i] * envelope[i + lag]
                }
            }
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }

        val bpm = (60.0 * sr) / bestLag
        return bpm.coerceIn(55.0, 210.0)
    }

    private fun computeEnvelope(samples: List<Float>, sr: Int): FloatArray {
        val frameSize = sr / 20
        val numFrames = samples.size / frameSize
        val envelope = FloatArray(numFrames)
        for (i in 0 until numFrames) {
            var sum = 0.0
            for (j in 0 until frameSize) {
                val idx = i * frameSize + j
                if (idx < samples.size) {
                    sum += samples[idx] * samples[idx]
                }
            }
            envelope[i] = sqrt(sum / frameSize).toFloat()
        }

        val diff = FloatArray(envelope.size)
        diff[0] = 0f
        for (i in 1 until envelope.size) {
            diff[i] = max(0f, envelope[i] - envelope[i - 1])
        }
        return diff
    }

    private fun computeRmsEnergy(samples: List<Float>): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) {
            sum += s * s
        }
        return sqrt(sum / samples.size)
    }

    private fun computeSpectralCentroid(samples: List<Float>, sr: Int): Double {
        if (samples.isEmpty()) return 0.0
        val fftSize = 2048
        val fft = FFT(fftSize)
        val buffer = FloatArray(fftSize)

        val startIdx = max(0, samples.size / 2 - fftSize / 2)
        for (i in 0 until fftSize) {
            buffer[i] = if (startIdx + i < samples.size) samples[startIdx + i] else 0f
        }

        val magnitudes = FloatArray(fftSize / 2)
        fft.forwardTransform(buffer)
        fft.modulus(buffer, magnitudes)

        var weightedSum = 0.0
        var magnitudeSum = 0.0
        val binWidth = sr.toDouble() / fftSize

        for (i in magnitudes.indices) {
            val freq = i * binWidth
            weightedSum += freq * magnitudes[i]
            magnitudeSum += magnitudes[i]
        }

        return if (magnitudeSum > 0) weightedSum / magnitudeSum else 0.0
    }

    private fun estimateInvalidTailSec(samples: List<Float>, sr: Int): Double {
        if (samples.isEmpty()) return 0.0

        val frameSize = sr / 20
        val numFrames = samples.size / frameSize
        if (numFrames == 0) return 0.0

        val rmsValues = FloatArray(numFrames)
        for (i in 0 until numFrames) {
            var sum = 0.0
            for (j in 0 until frameSize) {
                val idx = i * frameSize + j
                if (idx < samples.size) sum += samples[idx] * samples[idx]
            }
            rmsValues[i] = sqrt(sum / frameSize).toFloat()
        }

        val smooth = FloatArray(rmsValues.size)
        val kernel = 5
        for (i in rmsValues.indices) {
            var sum = 0.0
            var count = 0
            for (j in -kernel / 2..kernel / 2) {
                val idx = i + j
                if (idx in rmsValues.indices) {
                    sum += rmsValues[idx]
                    count++
                }
            }
            smooth[i] = (sum / count).toFloat()
        }

        val peakRms = smooth.maxOrNull() ?: 0f
        if (peakRms <= 1e-7) return samples.size.toDouble() / sr

        val sortedSmooth = smooth.sorted()
        val floorRms = sortedSmooth[smooth.size * 40 / 100]
        val activeThreshold = max(peakRms * 0.065f, max(floorRms * 0.62f, 4e-5f))

        var lastActiveIdx = -1
        for (i in smooth.indices.reversed()) {
            if (smooth[i] >= activeThreshold) {
                lastActiveIdx = i
                break
            }
        }

        val tailLenSec = samples.size.toDouble() / sr
        val invalidTail = if (lastActiveIdx >= 0) {
            max(0.0, tailLenSec - (lastActiveIdx * frameSize.toDouble() / sr))
        } else {
            tailLenSec
        }

        return if (invalidTail < 0.4) 0.0 else min(40.0, invalidTail)
    }
}
