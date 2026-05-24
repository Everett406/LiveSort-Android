package com.livesort.android.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import be.tarsos.dsp.util.fft.FFT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Android 音频分析器实现
 *
 * 使用 Android MediaCodec 解码 + TarsosDSP FFT 分析
 */
class AudioAnalyzer(private val context: Context) {

    private val targetSampleRate = 22050

    suspend fun analyze(filePath: String): AudioFeatures? = withContext(Dispatchers.Default) {
        val uri = Uri.parse(filePath)
        val isContentUri = uri.scheme == "content"

        // If it's a plain file path, verify existence
        if (!isContentUri) {
            val file = File(filePath)
            if (!file.exists()) return@withContext null
        }

        try {
            val durationSec = getDuration(uri, filePath)
            if (durationSec <= 0) {
                Log.w("AudioAnalyzer", "duration <= 0, skip: $filePath")
                return@withContext null
            }
            val (origSampleRate, fullSamples) = decodeAudio(uri, filePath, durationSec)
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
        } catch (e: Throwable) {
            Log.e("AudioAnalyzer", "analyze crashed for $filePath", e)
            null
        }
    }

    // --- Audio Decoding (MediaExtractor + MediaCodec) ---

    private fun getDuration(uri: Uri, filePath: String): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "content") {
                retriever.setDataSource(context, uri)
            } else {
                retriever.setDataSource(filePath)
            }
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            (durationStr?.toLong() ?: 0L) / 1000.0
        } catch (e: Exception) {
            0.0
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun decodeAudio(uri: Uri, filePath: String, maxDurationSec: Double): Pair<Int, FloatArray> {
        if (maxDurationSec <= 0) return Pair(0, FloatArray(0))

        val extractor = MediaExtractor()
        try {
            if (uri.scheme == "content") {
                extractor.setDataSource(context, uri, null)
            } else {
                extractor.setDataSource(filePath)
            }
        } catch (e: Exception) {
            Log.e("AudioAnalyzer", "setDataSource failed: $filePath", e)
            try { extractor.release() } catch (_: Exception) {}
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
            try { extractor.release() } catch (_: Exception) {}
            return Pair(0, FloatArray(0))
        }

        extractor.selectTrack(audioTrackIndex)
        var format = extractor.getTrackFormat(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
        var originalSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
        var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
        if (channelCount <= 0) channelCount = 2

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            android.util.Log.e("AudioAnalyzer", "createDecoder failed for $mime", e)
            try { extractor.release() } catch (_: Exception) {}
            return Pair(0, FloatArray(0))
        }

        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            android.util.Log.e("AudioAnalyzer", "codec configure/start failed", e)
            try { codec.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            return Pair(0, FloatArray(0))
        }

        val bufferInfo = MediaCodec.BufferInfo()
        val samples = mutableListOf<Float>()
        var isEOS = false
        // Cap at 5 minutes of audio to prevent memory issues
        val maxSamples = min((originalSampleRate * maxDurationSec).toLong(), originalSampleRate * 300L)

        try {
            while (!isEOS && samples.size < maxSamples) {
                if (!isEOS) {
                    val inputBufferId = codec.dequeueInputBuffer(10000)
                    if (inputBufferId >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferId)
                        if (inputBuffer == null) {
                            android.util.Log.w("AudioAnalyzer", "inputBuffer is null")
                            continue
                        }
                        val sampleSize = try {
                            extractor.readSampleData(inputBuffer, 0)
                        } catch (e: Exception) {
                            android.util.Log.e("AudioAnalyzer", "readSampleData failed", e)
                            -1
                        }
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
                        val outputBuffer = codec.getOutputBuffer(outputBufferId)
                        if (outputBuffer == null) {
                            android.util.Log.w("AudioAnalyzer", "outputBuffer is null")
                            continue
                        }
                        val pcmData = decodeOutputBuffer(outputBuffer, bufferInfo, channelCount)
                        samples.addAll(pcmData)
                        codec.releaseOutputBuffer(outputBufferId, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            isEOS = true
                        }
                    }
                    outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        format = codec.outputFormat
                        channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
                        originalSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
                        if (channelCount <= 0) channelCount = 2
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("AudioAnalyzer", "decode loop crashed", e)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }

        return Pair(originalSampleRate, samples.toFloatArray())
    }

    private fun decodeOutputBuffer(buffer: ByteBuffer, info: MediaCodec.BufferInfo, channelCount: Int): List<Float> {
        if (channelCount <= 0 || info.size <= 0) return emptyList()
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)

        val samples = mutableListOf<Float>()
        val shortBuffer = buffer.asShortBuffer()
        val numShorts = shortBuffer.remaining()
        if (numShorts <= 0) return emptyList()

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
