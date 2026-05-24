package com.livesort.android.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import be.tarsos.dsp.util.fft.FFT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Android 音频分析器实现
 *
 * 使用 Android MediaCodec 解码 + TarsosDSP FFT 分析
 */
actual class AudioAnalyzer actual constructor() {

    private val targetSampleRate = 22050

    actual suspend fun analyze(filePath: String): AudioFeatures? = withContext(Dispatchers.Default) {
        val file = File(filePath)
        if (!file.exists()) return@withContext null

        try {
            val durationSec = getDuration(filePath)
            val (origSampleRate, fullSamples) = decodeAudio(filePath, durationSec)
            if (fullSamples.isEmpty()) return@withContext null

            val samples = if (origSampleRate != targetSampleRate) {
                resample(fullSamples, origSampleRate, targetSampleRate)
            } else {
                fullSamples
            }
            val sr = targetSampleRate

            val mainSamples = samples.take((min(30.0, durationSec) * sr).toInt())
            val startSamples = samples.take((min(15.0, durationSec) * sr).toInt())
            val endStartIdx = max(0, samples.size - (min(15.0, durationSec) * sr).toInt())
            val endSamples = samples.drop(endStartIdx)
            val tailScanStartIdx = max(0, samples.size - (min(40.0, durationSec) * sr).toInt())
            val tailScanSamples = samples.drop(tailScanStartIdx)

            val start10sSamples = startSamples.take(sr * 10)
            val end10sSamples = endSamples.takeLast(sr * 10)

            val bpm = estimateBpm(mainSamples, sr)
            val startBpm = estimateBpm(startSamples, sr)
            val endBpm = estimateBpm(endSamples, sr)

            val energy = computeRmsEnergy(mainSamples)
            val startEnergy = computeRmsEnergy(startSamples)
            val endEnergy = computeRmsEnergy(endSamples)
            val start10sEnergy = computeRmsEnergy(start10sSamples)
            val end10sEnergy = computeRmsEnergy(end10sSamples)

            val brightness = computeSpectralCentroid(mainSamples, sr)

            val invalidTailSec = estimateInvalidTailSec(tailScanSamples, sr)
            val dynamicWindowSec = (10.0 + invalidTailSec * 0.55).coerceIn(8.0, 24.0)
            val dynamicWindowSamples = (sr * dynamicWindowSec).toInt()
            val effectiveTailSamples = (sr * invalidTailSec).toInt()

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

    // --- Audio Decoding (MediaExtractor + MediaCodec) ---

    private fun getDuration(filePath: String): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            (durationStr?.toLong() ?: 0L) / 1000.0
        } catch (e: Exception) {
            0.0
        } finally {
            retriever.release()
        }
    }

    private fun decodeAudio(filePath: String, maxDurationSec: Double): Pair<Int, FloatArray> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(filePath)
        } catch (e: Exception) {
            e.printStackTrace()
            return Pair(0, FloatArray(0))
        }

        var audioTrackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                break
            }
        }

        if (audioTrackIndex < 0) {
            extractor.release()
            return Pair(0, FloatArray(0))
        }

        extractor.selectTrack(audioTrackIndex)
        val format = extractor.getTrackFormat(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
        val originalSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val samples = mutableListOf<Float>()
        var isEOS = false
        val maxSamples = (originalSampleRate * maxDurationSec).toLong()

        try {
            while (!isEOS && samples.size < maxSamples) {
                if (!isEOS) {
                    val inputBufferId = codec.dequeueInputBuffer(10000)
                    if (inputBufferId >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferId) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            codec.queueInputBuffer(inputBufferId, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    outputBufferId >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputBufferId) ?: continue
                        val pcmData = decodeOutputBuffer(outputBuffer, bufferInfo, channelCount)
                        samples.addAll(pcmData)
                        codec.releaseOutputBuffer(outputBufferId, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            isEOS = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        return Pair(originalSampleRate, samples.toFloatArray())
    }

    private fun decodeOutputBuffer(buffer: ByteBuffer, info: MediaCodec.BufferInfo, channelCount: Int): List<Float> {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)

        val samples = mutableListOf<Float>()
        val shortBuffer = buffer.asShortBuffer()
        val numShorts = shortBuffer.remaining()

        if (channelCount == 1) {
            for (i in 0 until numShorts) {
                samples.add(shortBuffer.get(i) / 32768f)
            }
        } else {
            val frames = numShorts / channelCount
            for (i in 0 until frames) {
                var sum = 0f
                for (ch in 0 until channelCount) {
                    sum += shortBuffer.get(i * channelCount + ch) / 32768f
                }
                samples.add(sum / channelCount)
            }
        }
        return samples
    }

    private fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return samples
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val newSize = (samples.size / ratio).toInt()
        val result = FloatArray(newSize)
        for (i in 0 until newSize) {
            val srcIndex = (i * ratio).toInt()
            result[i] = samples[min(srcIndex, samples.size - 1)]
        }
        return result
    }

    // --- Feature Extraction ---

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
            diff[i] = kotlin.math.max(0f, envelope[i] - envelope[i - 1])
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
        val activeThreshold = kotlin.math.max(peakRms * 0.065f, kotlin.math.max(floorRms * 0.62f, 4e-5f))

        var lastActiveIdx = -1
        for (i in smooth.indices.reversed()) {
            if (smooth[i] >= activeThreshold) {
                lastActiveIdx = i
                break
            }
        }

        val tailLenSec = samples.size.toDouble() / sr
        val invalidTail = if (lastActiveIdx >= 0) {
            kotlin.math.max(0.0, tailLenSec - (lastActiveIdx * frameSize.toDouble() / sr))
        } else {
            tailLenSec
        }

        return if (invalidTail < 0.4) 0.0 else min(40.0, invalidTail)
    }
}
