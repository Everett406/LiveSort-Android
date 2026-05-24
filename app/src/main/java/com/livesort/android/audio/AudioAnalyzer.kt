package com.livesort.android.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.beatroot.BeatRootOnsetEventHandler
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.onsets.ComplexOnsetDetector
import be.tarsos.dsp.onsets.OnsetHandler
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
 * 完全对齐原作者 Python 实现 (audio_analyzer.py)
 */
class AudioAnalyzer(private val context: Context) {

    private val targetSampleRate = 22050

    // librosa.feature.rms 默认参数
    private val rmsFrameLength = 2048
    private val rmsHopLength = 512

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

            // 对齐 Python：前30s用于整体特征
            val mainCount = min((min(30.0, durationSec) * sr).toInt(), samples.size)
            val mainSamples = samples.copyOfRange(0, mainCount)

            // 对齐 Python：首尾15s用于过渡分析
            val startCount = min((min(15.0, durationSec) * sr).toInt(), samples.size)
            val startSamples = samples.copyOfRange(0, startCount)

            val endCount = (min(15.0, durationSec) * sr).toInt()
            val endStartIdx = max(0, samples.size - endCount)
            val endSamples = samples.copyOfRange(endStartIdx, samples.size)

            // 对齐 Python：尾部40s（或整个文件）用于 invalid tail 扫描
            val tailScanWindowSec = min(40.0, durationSec)
            val tailCount = (tailScanWindowSec * sr).toInt()
            val tailScanStartIdx = max(0, samples.size - tailCount)
            val tailScanSamples = samples.copyOfRange(tailScanStartIdx, samples.size)

            // 首尾10s mix window
            val mixWindowSamples = sr * 10
            val start10sSamples = startSamples.copyOfRange(0, min(mixWindowSamples, startSamples.size))
            val end10sStart = max(0, endSamples.size - mixWindowSamples)
            val end10sSamples = endSamples.copyOfRange(end10sStart, endSamples.size)

            // BPM（整体、开头、结尾）
            val bpm = estimateBpm(mainSamples, sr)
            val startBpm = estimateBpm(startSamples, sr)
            val endBpm = estimateBpm(endSamples, sr)

            // Energy（整体、开头、结尾、10s窗口）
            val energy = computeRmsEnergy(mainSamples)
            val startEnergy = computeRmsEnergy(startSamples)
            val endEnergy = computeRmsEnergy(endSamples)
            val start10sEnergy = computeRmsEnergy(start10sSamples)
            val end10sEnergy = computeRmsEnergy(end10sSamples)

            // Brightness
            val brightness = computeSpectralCentroid(mainSamples, sr)

            // 尾部静默分析（对齐 Python extract_features 尾部分析）
            val (tailSilenceSec, endActivityRatio) = estimateTailSilence(endSamples, sr)

            // Invalid tail 分析（对齐 Python estimate_invalid_tail_sec）
            val invalidTailSec = estimateInvalidTailSec(tailScanSamples, sr)

            // 动态窗口（对齐 Python）
            val dynamicWindowSec = (10.0 + invalidTailSec * 0.55).coerceIn(8.0, 24.0)
            val dynamicWindowSamples = (sr * dynamicWindowSec).toInt()
            val effectiveTailSamples = (sr * invalidTailSec).toInt()

            val endDynamicSamples = if (effectiveTailSamples > 0 && tailScanSamples.size > effectiveTailSamples) {
                val from = max(0, tailScanSamples.size - effectiveTailSamples - dynamicWindowSamples)
                val to = tailScanSamples.size - effectiveTailSamples
                tailScanSamples.copyOfRange(from, to)
            } else {
                val from = max(0, tailScanSamples.size - dynamicWindowSamples)
                tailScanSamples.copyOfRange(from, tailScanSamples.size)
            }
            val startDynamicSamples = startSamples.copyOfRange(0, min(dynamicWindowSamples, startSamples.size))

            val startDynamicEnergy = computeRmsEnergy(startDynamicSamples)
            val endDynamicEnergy = computeRmsEnergy(endDynamicSamples)

            // Mix 参数（对齐 Python）
            val mixLeadSec = (9.1 + tailSilenceSec * 0.62).coerceIn(7.4, 13.2)
            val mixBreathSec = (1.75 + tailSilenceSec * 0.22).coerceIn(1.35, 3.2)
            val mixEffectStartSec = (10.0 + invalidTailSec).coerceIn(8.0, 50.0)
            val mixEntrySec = (4.0 + invalidTailSec).coerceIn(4.0, 44.0)

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
                tailScanWindowSec = tailScanWindowSec,
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
        // Cap at 5 minutes of audio to prevent memory issues
        val maxSamples = min((originalSampleRate * maxDurationSec).toLong(), originalSampleRate * 300L).toInt()
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

    // --- Feature Extraction ---

    /**
     * 对齐 librosa.feature.rms：计算帧级 RMS
     * frame_length=2048, hop_length=512
     */
    private fun computeFrameRms(samples: FloatArray, frameLength: Int = 2048, hopLength: Int = 512): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val numFrames = max(1, (samples.size - frameLength) / hopLength + 1)
        val rms = FloatArray(numFrames)
        for (i in 0 until numFrames) {
            var sum = 0.0
            for (j in 0 until frameLength) {
                val idx = i * hopLength + j
                if (idx < samples.size) {
                    sum += samples[idx] * samples[idx]
                }
            }
            rms[i] = sqrt(sum / frameLength).toFloat()
        }
        return rms
    }

    /**
     * 计算尾部静默时间和活跃度比例（对齐 Python extract_features 尾部分析）
     */
    private fun estimateTailSilence(endSamples: FloatArray, sr: Int): Pair<Double, Double> {
        if (endSamples.isEmpty()) return Pair(0.0, 1.0)

        val endRms = computeFrameRms(endSamples)
        if (endRms.isEmpty()) return Pair(0.0, 1.0)

        val peakRms = endRms.maxOrNull() ?: 0f
        val activeThreshold = max(peakRms * 0.2f, 1e-4f)

        var lastActiveIdx = -1
        for (i in endRms.indices) {
            if (endRms[i] >= activeThreshold) {
                lastActiveIdx = i
            }
        }

        val endLenSec = endSamples.size.toDouble() / sr
        val lastActiveSec = if (lastActiveIdx >= 0) {
            lastActiveIdx * rmsHopLength.toDouble() / sr
        } else {
            0.0
        }
        val tailSilenceSec = max(0.0, endLenSec - lastActiveSec)
        val endActivityRatio = ((endLenSec - tailSilenceSec) / max(endLenSec, 1e-6)).coerceIn(0.0, 1.0)

        return Pair(tailSilenceSec, endActivityRatio)
    }

    /**
     * 估计无效尾部时间（对齐 Python estimate_invalid_tail_sec）
     */
    private fun estimateInvalidTailSec(tailScanSamples: FloatArray, sr: Int): Double {
        if (tailScanSamples.isEmpty()) return 0.0

        val tailRms = computeFrameRms(tailScanSamples)
        if (tailRms.isEmpty()) return 0.0

        // 5-point moving average smooth (mode='same')
        val smooth = FloatArray(tailRms.size)
        val kernelSize = 5
        for (i in tailRms.indices) {
            var sum = 0.0
            var count = 0
            for (j in -kernelSize / 2..kernelSize / 2) {
                val idx = i + j
                if (idx in tailRms.indices) {
                    sum += tailRms[idx]
                    count++
                }
            }
            smooth[i] = (sum / count).toFloat()
        }

        val peakRms = tailRms.maxOrNull() ?: 0f
        if (peakRms <= 1e-7) return tailScanSamples.size.toDouble() / sr

        // 40th percentile
        val sortedSmooth = smooth.sorted()
        val floorRms = sortedSmooth[smooth.size * 40 / 100]
        val activeThreshold = max(peakRms * 0.065f, max(floorRms * 0.62f, 4e-5f))

        var lastActiveIdx = -1
        for (i in smooth.indices) {
            if (smooth[i] >= activeThreshold) {
                lastActiveIdx = i
            }
        }

        val tailLenSec = tailScanSamples.size.toDouble() / sr
        val invalidTail = if (lastActiveIdx >= 0) {
            max(0.0, tailLenSec - (lastActiveIdx * rmsHopLength.toDouble() / sr))
        } else {
            tailLenSec
        }

        return if (invalidTail < 0.4) 0.0 else min(40.0, invalidTail)
    }

    private fun estimateBpm(samples: FloatArray, sr: Int): Double {
        if (samples.size < sr * 5) return 0.0

        return try {
            // 主算法：频谱差分 onset envelope + 自相关 + log-normal 先验
            // 对齐 librosa.feature.tempo 的核心逻辑
            val onsetEnv = computeOnsetEnvelopeSpectral(samples, sr)
            estimateBpmFromOnsetEnvelope(onsetEnv, sr)
        } catch (e: Exception) {
            Log.w("AudioAnalyzer", "Spectral onset BPM failed, fallback to BeatRoot", e)
            try {
                estimateBpmWithBeatRoot(samples, sr)
            } catch (e2: Exception) {
                Log.w("AudioAnalyzer", "BeatRoot BPM failed, fallback to autocorrelation", e2)
                val onsetDiff = computeOnsetEnvelope(samples, sr)
                estimateBpmByAutocorrelation(onsetDiff, sr)
            }
        }
    }

    /**
     * 计算频谱差分 onset envelope
     * 对齐 librosa.onset.onset_strength 的简化实现：
     * 1. STFT 分帧 (frame=2048, hop=512)
     * 2. 计算每帧对数幅度谱
     * 3. 相邻帧差分，保留正值并求和
     */
    private fun computeOnsetEnvelopeSpectral(samples: FloatArray, sr: Int): FloatArray {
        val frameSize = 2048
        val hopSize = 512
        val numFrames = max(0, (samples.size - frameSize) / hopSize + 1)
        if (numFrames <= 1) return FloatArray(0)

        val fft = FFT(frameSize)
        val buffer = FloatArray(frameSize)
        val magnitudes = FloatArray(frameSize / 2)
        val onsetEnvelope = FloatArray(numFrames)

        var prevLogMag: FloatArray? = null

        for (i in 0 until numFrames) {
            val start = i * hopSize
            for (j in 0 until frameSize) {
                buffer[j] = if (start + j < samples.size) samples[start + j] else 0f
            }

            // 复制 buffer 因为 forwardTransform 会修改它
            val frameBuffer = buffer.copyOf()
            fft.forwardTransform(frameBuffer)
            fft.modulus(frameBuffer, magnitudes)

            // 对数压缩幅度谱
            val logMag = FloatArray(magnitudes.size) { j ->
                kotlin.math.ln(1.0 + magnitudes[j].toDouble()).toFloat()
            }

            if (prevLogMag != null) {
                var diffSum = 0.0
                for (j in logMag.indices) {
                    val diff = logMag[j] - prevLogMag[j]
                    if (diff > 0) {
                        diffSum += diff
                    }
                }
                onsetEnvelope[i] = diffSum.toFloat()
            } else {
                onsetEnvelope[i] = 0f
            }

            prevLogMag = logMag
        }

        return onsetEnvelope
    }

    /**
     * 基于 onset envelope 自相关 + log-normal 先验的 tempo 估计
     * 对齐 librosa.feature.tempo 核心逻辑：
     * 1. 8秒窗口自相关 (ac_size=8.0)
     * 2. 转换为 BPM 轴
     * 3. log-normal 先验加权 (start_bpm=120, std_bpm=1.0)
     * 4. 取 argmax
     */
    private fun estimateBpmFromOnsetEnvelope(onsetEnv: FloatArray, sr: Int): Double {
        if (onsetEnv.size < 10) return 0.0

        val hopSize = 512
        val acSizeSec = 8.0
        val winLength = ((sr * acSizeSec) / hopSize).toInt()
        val effectiveWinLength = min(winLength, onsetEnv.size)

        // lag 范围对应 55~210 BPM
        val minLag = max(1, (sr * 60.0 / 210.0 / hopSize).toInt())
        val maxLag = min((sr * 60.0 / 55.0 / hopSize).toInt(), onsetEnv.size - 1)
        if (minLag >= maxLag) return 0.0

        val numLags = maxLag - minLag + 1
        val autocorr = DoubleArray(numLags)

        for (lag in minLag..maxLag) {
            var sum = 0.0
            val maxT = min(onsetEnv.size - lag, effectiveWinLength)
            for (t in 0 until maxT) {
                sum += onsetEnv[t] * onsetEnv[t + lag]
            }
            autocorr[lag - minLag] = sum
        }

        val maxCorr = autocorr.maxOrNull() ?: 1.0
        if (maxCorr <= 0) return 0.0

        // 转换为 BPM 轴
        val bpms = DoubleArray(numLags) { i ->
            val lag = minLag + i
            60.0 * sr / (lag * hopSize)
        }

        // log-normal 先验，中心 120 BPM
        val startBpm = 120.0
        val stdBpm = 1.0
        val logPrior = DoubleArray(numLags) { i ->
            val bpm = bpms[i]
            val logRatio = (kotlin.math.log2(bpm) - kotlin.math.log2(startBpm)) / stdBpm
            -0.5 * logRatio * logRatio
        }

        // 加权找峰值 (对齐 librosa: np.argmax(np.log1p(1e6 * tg) + logprior))
        var bestIdx = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in autocorr.indices) {
            val score = kotlin.math.ln(1.0 + 1e6 * autocorr[i] / maxCorr) + logPrior[i]
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }

        return bpms[bestIdx].coerceIn(55.0, 210.0)
    }

    /**
     * 使用 TarsosDSP BeatRoot 算法检测 BPM (fallback)
     */
    private fun estimateBpmWithBeatRoot(samples: FloatArray, sr: Int): Double {
        val frameSize = 1024
        val hopSize = 512

        val format = TarsosDSPAudioFormat(sr.toFloat(), 16, 1, true, false)
        val audioEvent = AudioEvent(format)

        val broeh = BeatRootOnsetEventHandler()
        val detector = ComplexOnsetDetector(frameSize)
        detector.setHandler(broeh)

        var samplesProcessed = 0L
        val bytesPerSample = 2 // 16-bit mono

        for (start in 0 until samples.size step hopSize) {
            val end = min(start + frameSize, samples.size)
            val frame = samples.copyOfRange(start, end)

            val paddedFrame = if (frame.size < frameSize) {
                FloatArray(frameSize) { i -> if (i < frame.size) frame[i] else 0f }
            } else {
                frame
            }

            audioEvent.setFloatBuffer(paddedFrame)
            audioEvent.setBytesProcessed(samplesProcessed * bytesPerSample)

            detector.process(audioEvent)
            samplesProcessed += hopSize
        }

        detector.processingFinished()

        val beats = mutableListOf<Double>()
        broeh.trackBeats(object : OnsetHandler {
            override fun handleOnset(time: Double, salience: Double) {
                beats.add(time)
            }
        })

        if (beats.size < 2) {
            throw IllegalStateException("Not enough beats detected: ${beats.size}")
        }

        val intervals = mutableListOf<Double>()
        for (i in 1 until beats.size) {
            intervals.add(beats[i] - beats[i - 1])
        }

        val validIntervals = intervals.filter { it in 0.28..1.1 }
        if (validIntervals.isEmpty()) {
            throw IllegalStateException("No valid beat intervals")
        }

        val medianInterval = validIntervals.sorted()[validIntervals.size / 2]
        val bpm = 60.0 / medianInterval

        val candidates = listOf(bpm, bpm * 2, bpm / 2)
        val bestBpm = candidates.filter { it in 55.0..210.0 }.minByOrNull { candidate ->
            validIntervals.sumOf { interval ->
                val expectedInterval = 60.0 / candidate
                val ratio = interval / expectedInterval
                val error = if (ratio in 0.92..1.08 || ratio in 1.92..2.08 || ratio in 0.42..0.58) {
                    0.0
                } else {
                    kotlin.math.abs(ratio - 1.0)
                }
                error
            }
        } ?: bpm

        return bestBpm.coerceIn(55.0, 210.0)
    }

    private fun estimateBpmByAutocorrelation(envelope: FloatArray, sr: Int): Double {
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

    private fun computeOnsetEnvelope(samples: FloatArray, sr: Int): FloatArray {
        val frameSize = sr / 20  // 50ms frames
        val numFrames = samples.size / frameSize
        val energy = FloatArray(numFrames)
        for (i in 0 until numFrames) {
            var sum = 0.0
            for (j in 0 until frameSize) {
                val idx = i * frameSize + j
                if (idx < samples.size) {
                    sum += samples[idx] * samples[idx]
                }
            }
            energy[i] = sqrt(sum / frameSize).toFloat()
        }

        val diff = FloatArray(energy.size)
        diff[0] = 0f
        for (i in 1 until energy.size) {
            diff[i] = kotlin.math.max(0f, energy[i] - energy[i - 1])
        }
        return diff
    }

    private fun smoothFloatArray(data: FloatArray, windowSize: Int): FloatArray {
        if (data.isEmpty()) return FloatArray(0)
        val half = windowSize / 2
        val result = FloatArray(data.size)
        for (i in data.indices) {
            var sum = 0.0
            var count = 0
            for (j in -half..half) {
                val idx = i + j
                if (idx in data.indices) {
                    sum += data[idx]
                    count++
                }
            }
            result[i] = (sum / count).toFloat()
        }
        return result
    }

    private fun computeRmsEnergy(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) {
            sum += s * s
        }
        return sqrt(sum / samples.size)
    }

    private fun computeSpectralCentroid(samples: FloatArray, sr: Int): Double {
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
}
