package com.livesort.android.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Android 音频分析器实现
 *
 * 完全对齐原作者 JS 实现 (index.html analyzeLocalAudioFile)
 * - BPM: 平均绝对值 envelope + 自相关
 * - Energy: frame RMS 均值
 * - Brightness: 时域差分/绝对值比
 */
class AudioAnalyzer(private val context: Context) {

    private val targetSampleRate = 22050

    suspend fun analyze(filePath: String): AudioFeatures? = withContext(Dispatchers.Default) {
        val uri = Uri.parse(filePath)
        val isContentUri = uri.scheme == "content"

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

            // 分块解码：前30秒(main+start) + 后40秒(tail+end)
            val frontNeedSec = 30.0
            val tailNeedSec = 40.0
            val (sr, frontSamples, tailSamples) = decodeFrontAndTail(
                uri, filePath, durationSec, frontNeedSec, tailNeedSec
            )
            if (frontSamples.isEmpty()) return@withContext null

            val samples = if (sr != targetSampleRate) {
                val rsFront = resample(frontSamples, sr, targetSampleRate)
                val rsTail = if (tailSamples.isNotEmpty()) resample(tailSamples, sr, targetSampleRate) else FloatArray(0)
                Pair(rsFront, rsTail)
            } else {
                Pair(frontSamples, tailSamples)
            }
            val front = samples.first
            val tail = samples.second
            val tsr = targetSampleRate

            // 子样本切片（对齐原作者）
            val mainCount = min((min(30.0, durationSec) * tsr).toInt(), front.size)
            val mainSamples = front.copyOfRange(0, mainCount)

            val startCount = min((min(15.0, durationSec) * tsr).toInt(), front.size)
            val startSamples = front.copyOfRange(0, startCount)

            val endSamples = if (tail.isNotEmpty()) {
                val endCount = (min(15.0, durationSec) * tsr).toInt()
                val startIdx = max(0, tail.size - endCount)
                tail.copyOfRange(startIdx, tail.size)
            } else {
                val endCount = (min(15.0, durationSec) * tsr).toInt()
                val startIdx = max(0, front.size - endCount)
                front.copyOfRange(startIdx, front.size)
            }

            val tailScanSamples = if (tail.isEmpty()) front else tail

            // 首尾10s mix window
            val mixWindowSamples = tsr * 10
            val start10sSamples = startSamples.copyOfRange(0, min(mixWindowSamples, startSamples.size))
            val end10sStart = max(0, endSamples.size - mixWindowSamples)
            val end10sSamples = endSamples.copyOfRange(end10sStart, endSamples.size)

            // --- 并行计算独立特征 ---
            val (bpm, startBpm, endBpm) = coroutineScope {
                val d1 = async { estimateBpm(mainSamples, tsr) }
                val d2 = async { estimateBpm(startSamples, tsr) }
                val d3 = async { estimateBpm(endSamples, tsr) }
                Triple(d1.await(), d2.await(), d3.await())
            }

            val (energy, startEnergy, endEnergy, start10sEnergy, end10sEnergy) = coroutineScope {
                val d1 = async { computeEnergyFromSamples(mainSamples) }
                val d2 = async { computeEnergyFromSamples(startSamples) }
                val d3 = async { computeEnergyFromSamples(endSamples) }
                val d4 = async { computeEnergyFromSamples(start10sSamples) }
                val d5 = async { computeEnergyFromSamples(end10sSamples) }
                Quintuple(d1.await(), d2.await(), d3.await(), d4.await(), d5.await())
            }

            val brightness = computeBrightnessFromSamples(mainSamples, tsr)

            // 尾部分析（对齐原作者）
            val frameRms = computeFrameRmsSeries(endSamples, 2048, 512)
            val peakRms = frameRms.maxOrNull() ?: 0f
            val activeThreshold = max(peakRms * 0.2f, 1e-4f)
            var lastActiveIdx = -1
            for (i in frameRms.indices) {
                if (frameRms[i] >= activeThreshold) {
                    lastActiveIdx = i
                }
            }
            val endLenSec = endSamples.size.toDouble() / tsr
            val lastActiveSec = if (lastActiveIdx >= 0) {
                lastActiveIdx * 512.0 / tsr
            } else {
                0.0
            }
            val tailSilenceSec = max(0.0, endLenSec - lastActiveSec)
            val endActivityRatio = if (endLenSec > 0) {
                ((endLenSec - tailSilenceSec) / endLenSec).coerceIn(0.0, 1.0)
            } else {
                1.0
            }

            val mixLeadSec = (9.1 + tailSilenceSec * 0.62).coerceIn(7.4, 13.2)
            val mixBreathSec = (1.75 + tailSilenceSec * 0.22).coerceIn(1.35, 3.2)

            val tailRmsSeries = computeFrameRmsSeries(tailScanSamples, 2048, 512)
            val invalidTailSec = estimateInvalidTailSecFromRmsSeries(tailRmsSeries, tsr, 512)

            val mixEffectStartSec = (10.0 + invalidTailSec).coerceIn(8.0, 50.0)
            val mixEntrySec = (4.0 + invalidTailSec).coerceIn(4.0, 44.0)
            val dynamicWindowSec = (10.0 + invalidTailSec * 0.55).coerceIn(8.0, 24.0)
            val dynamicWindowSamples = (tsr * dynamicWindowSec).toInt()
            val effectiveTailSamples = (tsr * invalidTailSec).toInt()

            val endDynamicSamples = if (effectiveTailSamples > 0 && tailScanSamples.size > effectiveTailSamples) {
                val from = max(0, tailScanSamples.size - effectiveTailSamples - dynamicWindowSamples)
                val to = tailScanSamples.size - effectiveTailSamples
                tailScanSamples.copyOfRange(from, to)
            } else {
                val from = max(0, tailScanSamples.size - dynamicWindowSamples)
                tailScanSamples.copyOfRange(from, tailScanSamples.size)
            }
            val startDynamicSamples = startSamples.copyOfRange(0, min(dynamicWindowSamples, startSamples.size))

            val startDynamicEnergy = computeEnergyFromSamples(startDynamicSamples)
            val endDynamicEnergy = computeEnergyFromSamples(endDynamicSamples)

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
                dynamicWindowSec = dynamicWindowSec,
                tailSilenceSec = tailSilenceSec,
                tailScanWindowSec = min(40.0, durationSec),
                invalidTailSec = invalidTailSec,
                endActivityRatio = endActivityRatio,
                mixLeadSec = mixLeadSec,
                mixBreathSec = mixBreathSec,
                mixEffectStartSec = mixEffectStartSec,
                mixEntrySec = mixEntrySec
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

    /**
     * 分块解码：返回 (sampleRate, frontSamples, tailSamples)
     * 对于短歌曲，tailSamples 为空，frontSamples 包含全部
     */
    private fun decodeFrontAndTail(
        uri: Uri, filePath: String,
        durationSec: Double, frontSec: Double, tailSec: Double
    ): Triple<Int, FloatArray, FloatArray> {
        if (durationSec <= frontSec + tailSec) {
            // 短歌曲：一次性解码全部
            val (sr, samples) = decodeAudioSegment(uri, filePath, 0.0, durationSec)
            return Triple(sr, samples, FloatArray(0))
        }

        // 长歌曲：分别解码前段和尾段
        val (sr1, front) = decodeAudioSegment(uri, filePath, 0.0, frontSec)
        if (front.isEmpty()) return Triple(0, FloatArray(0), FloatArray(0))

        val tailStartSec = max(0.0, durationSec - tailSec)
        val (sr2, tail) = decodeAudioSegment(uri, filePath, tailStartSec, tailSec)
        // 以实际解码到的采样率为准（前后段应该一致）
        return Triple(sr1, front, tail)
    }

    /**
     * 从指定秒数开始解码最多 maxDurationSec 秒
     * 使用 MediaExtractor.seekTo 跳到目标位置
     */
    private fun decodeAudioSegment(
        uri: Uri, filePath: String,
        startSec: Double, maxDurationSec: Double
    ): Pair<Int, FloatArray> {
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

        // Seek 到目标位置（微秒）
        if (startSec > 0) {
            try {
                extractor.seekTo((startSec * 1_000_000).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            } catch (e: Exception) {
                Log.w("AudioAnalyzer", "seekTo failed, fallback to beginning", e)
            }
        }

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            Log.e("AudioAnalyzer", "createDecoder failed for $mime", e)
            try { extractor.release() } catch (_: Exception) {}
            return Pair(0, FloatArray(0))
        }

        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            Log.e("AudioAnalyzer", "codec configure/start failed", e)
            try { codec.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            return Pair(0, FloatArray(0))
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var isEOS = false
        val maxSamples = min(
            (originalSampleRate * maxDurationSec).toLong(),
            originalSampleRate * 300L
        ).toInt()
        val samples = FloatArray(maxSamples)
        var writeIndex = 0

        try {
            while (!isEOS && writeIndex < maxSamples) {
                if (!isEOS) {
                    val inputBufferId = codec.dequeueInputBuffer(10000)
                    if (inputBufferId >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferId)
                        if (inputBuffer == null) {
                            Log.w("AudioAnalyzer", "inputBuffer is null")
                            continue
                        }
                        val sampleSize = try {
                            extractor.readSampleData(inputBuffer, 0)
                        } catch (e: Exception) {
                            Log.e("AudioAnalyzer", "readSampleData failed", e)
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
                            codec.releaseOutputBuffer(outputBufferId, false)
                            continue
                        }
                        val pcmData = decodeOutputBuffer(outputBuffer, bufferInfo, channelCount)
                        val toWrite = min(pcmData.size, maxSamples - writeIndex)
                        if (toWrite > 0) {
                            pcmData.copyInto(samples, writeIndex, 0, toWrite)
                            writeIndex += toWrite
                        }
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

        return Pair(originalSampleRate, samples.copyOf(writeIndex))
    }

    private fun decodeOutputBuffer(buffer: ByteBuffer, info: MediaCodec.BufferInfo, channelCount: Int): FloatArray {
        if (channelCount <= 0 || info.size <= 0) return FloatArray(0)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)

        val shortBuffer = buffer.asShortBuffer()
        val numShorts = shortBuffer.remaining()
        if (numShorts <= 0) return FloatArray(0)

        return if (channelCount == 1) {
            FloatArray(numShorts) { i -> shortBuffer.get(i) / 32768f }
        } else {
            val frames = numShorts / channelCount
            FloatArray(frames) { i ->
                var sum = 0f
                for (ch in 0 until channelCount) {
                    sum += shortBuffer.get(i * channelCount + ch) / 32768f
                }
                sum / channelCount
            }
        }
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

    // --- Feature Extraction (aligned with original author JS) ---

    /**
     * 对齐原作者 computeFrameRmsSeries
     */
    private fun computeFrameRmsSeries(samples: FloatArray, frameSize: Int, hopSize: Int): FloatArray {
        val safeFrame = max(32, frameSize)
        val safeHop = max(16, hopSize)
        if (samples.size < safeFrame) return FloatArray(0)
        val numFrames = (samples.size - safeFrame) / safeHop + 1
        val series = FloatArray(numFrames)
        for (i in 0 until numFrames) {
            val start = i * safeHop
            var sumSq = 0.0
            for (j in 0 until safeFrame) {
                val v = samples[start + j]
                sumSq += v * v
            }
            series[i] = sqrt(sumSq / safeFrame).toFloat()
        }
        return series
    }

    private fun smoothSeriesMovingAverage(values: FloatArray, kernelSize: Int = 5): FloatArray {
        if (values.isEmpty()) return FloatArray(0)
        val safeKernel = max(1, kernelSize)
        if (safeKernel <= 1 || values.size <= 2) return values.copyOf()
        val half = safeKernel / 2
        val out = FloatArray(values.size)
        for (i in values.indices) {
            var acc = 0.0
            var count = 0
            val start = max(0, i - half)
            val end = min(values.size - 1, i + half)
            for (j in start..end) {
                acc += values[j]
                count++
            }
            out[i] = if (count > 0) (acc / count).toFloat() else values[i]
        }
        return out
    }

    private fun percentile(values: FloatArray, p: Double): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return 0f
        val rank = (max(0.0, min(100.0, p)) / 100.0) * (sorted.size - 1)
        val low = rank.toInt()
        val high = kotlin.math.ceil(rank).toInt()
        if (low == high) return sorted[low]
        val t = (rank - low).toFloat()
        return sorted[low] * (1 - t) + sorted[high] * t
    }

    private fun estimateInvalidTailSecFromRmsSeries(rmsSeries: FloatArray, sampleRate: Int, hopSize: Int): Double {
        if (rmsSeries.isEmpty() || sampleRate <= 0 || hopSize <= 0) return 0.0
        val smoothed = smoothSeriesMovingAverage(rmsSeries, 5)
        val peakRms = rmsSeries.maxOrNull() ?: 0f
        val tailLenSec = (rmsSeries.size * hopSize).toDouble() / sampleRate
        if (peakRms <= 1e-7) return min(40.0, max(0.0, tailLenSec))
        val floorRms = percentile(smoothed, 40.0)
        val activeThreshold = max(peakRms * 0.065f, max(floorRms * 0.62f, 4e-5f))
        var lastActiveIndex = -1
        for (i in smoothed.indices) {
            if (smoothed[i] >= activeThreshold) lastActiveIndex = i
        }
        val invalidTail = if (lastActiveIndex < 0) {
            tailLenSec
        } else {
            val lastActiveSec = (lastActiveIndex * hopSize).toDouble() / sampleRate
            max(0.0, tailLenSec - lastActiveSec)
        }
        return if (invalidTail < 0.4) 0.0 else min(40.0, invalidTail)
    }

    /**
     * 对齐原作者 estimateBpmFromSamples
     */
    private fun estimateBpm(samples: FloatArray, sr: Int): Double {
        val frameSize = 1024
        val hopSize = 512
        if (samples.size < frameSize * 8 || sr <= 0) return 0.0

        val envSize = (samples.size - frameSize) / hopSize + 1
        val env = FloatArray(envSize)
        for (i in 0 until envSize) {
            val start = i * hopSize
            var sum = 0.0
            for (j in 0 until frameSize) {
                sum += kotlin.math.abs(samples[start + j])
            }
            env[i] = (sum / frameSize).toFloat()
        }
        if (envSize < 32) return 0.0

        val mean = env.sum() / envSize
        var varianceAcc = 0.0
        for (i in 0 until envSize) {
            env[i] = max(0f, env[i] - mean.toFloat())
            varianceAcc += env[i] * env[i]
        }
        val variance = varianceAcc / max(1, envSize)
        if (variance < 1e-6) return 0.0

        val envRate = sr.toDouble() / hopSize
        val minLag = ((60 * envRate) / 180).toInt()
        val maxLag = ((60 * envRate) / 70).toInt()
        var bestLag = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in max(1, minLag)..max(minLag + 1, maxLag)) {
            var score = 0.0
            for (i in 0 until envSize - lag) {
                score += env[i] * env[i + lag]
            }
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0) return 0.0
        val bpm = (60 * envRate) / bestLag
        return if (bpm.isFinite()) bpm.coerceIn(55.0, 210.0) else 0.0
    }

    /**
     * 对齐原作者 computeEnergyFromSamples
     */
    private fun computeEnergyFromSamples(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        val rmsSeries = computeFrameRmsSeries(samples, 2048, 512)
        if (rmsSeries.isEmpty()) return 0.0
        val mean = rmsSeries.sum().toDouble() / rmsSeries.size
        return if (mean.isFinite()) mean else 0.0
    }

    /**
     * 对齐原作者 computeBrightnessFromSamples
     */
    private fun computeBrightnessFromSamples(samples: FloatArray, sampleRate: Int): Double {
        if (samples.size < 512 || sampleRate <= 0) return 0.0
        val frameSize = 2048
        val hopSize = 1024
        var weighted = 0.0
        var sumWeight = 0
        for (start in 0 until samples.size - frameSize step hopSize) {
            var last = samples[start]
            var diffAcc = 0.0
            var absAcc = kotlin.math.abs(last)
            for (i in 1 until frameSize) {
                val cur = samples[start + i]
                diffAcc += kotlin.math.abs(cur - last)
                absAcc += kotlin.math.abs(cur)
                last = cur
            }
            val norm = if (absAcc > 1e-9) diffAcc / absAcc else 0.0
            weighted += norm
            sumWeight++
        }
        val normalized = if (sumWeight > 0) weighted / sumWeight else 0.0
        return max(0.0, min(6000.0, normalized * sampleRate * 0.14))
    }

    /** 简单的五元组数据类，用于并行返回多个 energy 值 */
    private data class Quintuple<A, B, C, D, E>(
        val first: A, val second: B, val third: C, val fourth: D, val fifth: E
    )
}
