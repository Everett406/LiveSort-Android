package com.livesort.android.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.livesort.android.model.Song
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无感过渡播放器
 *
 * 使用双 ExoPlayer 实例实现 Crossfade，完全对齐原作者混音逻辑：
 * - 自适应 Mix Entry（根据 BPM 差、响度差、尾部活跃度动态调整）
 * - 动态过渡 Profile（根据前后歌曲响度差异调整）
 * - 复杂 Crossfade 音量曲线（cosine fade + duck + shape + dynamic profile）
 */
class CrossfadePlayer(context: Context) {

    private val playerA: ExoPlayer = ExoPlayer.Builder(context).build()
    private val playerB: ExoPlayer = ExoPlayer.Builder(context).build()

    private var currentPlayer: ExoPlayer = playerA
    private var nextPlayer: ExoPlayer = playerB

    private var playlist: List<Song> = emptyList()
    private var currentIndex: Int = -1

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var fadeRunnable: Runnable? = null

    // 原作者常量
    private val EFFECT_RELEASE_START_INCOMING_SEC = 6.0
    private val EFFECT_RELEASE_END_INCOMING_SEC = 8.4
    private val EFFECT_MIN_MULTIPLIER = 0.0
    private val EFFECT_ENTRY_SMOOTH_RATIO = 0.3
    private val CROSSFADE_SEC = 10.0
    private val MIX_ENTRY_BEFORE_END_SEC = 4.0
    private val EFFECT_START_BEFORE_END_SEC = 10.0
    private val BASE_VOL = 1.0f

    init {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    if (currentPlayer == playerA || currentPlayer == playerB) {
                        _durationMs.value = currentPlayer.duration.coerceAtLeast(0)
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
        }
        playerA.addListener(listener)
        playerB.addListener(listener)
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        playlist = songs
        if (startIndex in songs.indices) {
            playAt(startIndex)
        }
    }

    fun playAt(index: Int) {
        if (index !in playlist.indices) return
        currentIndex = index
        val song = playlist[index]
        _currentSong.value = song

        stopCrossfade()

        currentPlayer.stop()
        currentPlayer.clearMediaItems()
        nextPlayer.stop()
        nextPlayer.clearMediaItems()

        val uri = resolveUri(song.filename)
        currentPlayer.setMediaItem(MediaItem.fromUri(uri))
        currentPlayer.prepare()
        currentPlayer.play()
        currentPlayer.volume = BASE_VOL

        _isPlaying.value = true
        _isTransitioning.value = false
        startProgressTracking()
    }

    fun playPause() {
        if (currentPlayer.isPlaying) {
            currentPlayer.pause()
        } else {
            currentPlayer.play()
        }
    }

    fun seekTo(positionMs: Long) {
        currentPlayer.seekTo(positionMs)
        _currentPositionMs.value = positionMs
    }

    fun next() {
        if (currentIndex + 1 < playlist.size) {
            playAt(currentIndex + 1)
        }
    }

    fun previous() {
        if (currentIndex > 0) {
            playAt(currentIndex - 1)
        }
    }

    // --- Progress Tracking & Auto Crossfade ---

    private fun startProgressTracking() {
        stopProgressTracking()
        progressRunnable = object : Runnable {
            override fun run() {
                val pos = currentPlayer.currentPosition.coerceAtLeast(0)
                val dur = currentPlayer.duration.coerceAtLeast(1)
                _currentPositionMs.value = pos
                _durationMs.value = dur
                _progress.value = pos.toFloat() / dur.toFloat()

                if (!_isTransitioning.value && currentIndex + 1 < playlist.size) {
                    val outgoingSong = playlist[currentIndex]
                    val incomingSong = playlist[currentIndex + 1]
                    val mixEntryMs = (getAdaptiveMixEntrySeconds(outgoingSong, incomingSong) * 1000).toLong()
                    val transitionStartMs = dur - mixEntryMs

                    if (pos >= transitionStartMs && dur > mixEntryMs) {
                        startCrossfade(incomingSong)
                    }
                }
                handler.postDelayed(this, 100)
            }
        }
        handler.post(progressRunnable!!)
    }

    private fun stopProgressTracking() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    // --- Crossfade Engine (aligned with original author) ---

    private fun startCrossfade(nextSong: Song) {
        if (_isTransitioning.value) return
        _isTransitioning.value = true

        val outgoingSong = playlist[currentIndex]
        val outgoingPlayer = currentPlayer
        val incomingPlayer = nextPlayer

        val uri = resolveUri(nextSong.filename)
        incomingPlayer.setMediaItem(MediaItem.fromUri(uri))
        incomingPlayer.volume = 0f
        incomingPlayer.prepare()
        incomingPlayer.play()

        val mixLead = getMixLeadSeconds(outgoingSong)
        val mixBreath = getMixBreathSeconds(outgoingSong)
        val durationSec = max(9.0, min(10.2, mixLead + 0.05))
        val breathRatio = min(0.46, max(0.2, mixBreath / durationSec))
        val dynamicProfile = getTransitionDynamicProfile(outgoingSong, nextSong)

        val initialEffectMultiplier = getCrossfadeEffectMultiplier(0.0)
        val initialState = getCrossfadeState(0.0, BASE_VOL.toDouble(), breathRatio, dynamicProfile, initialEffectMultiplier)

        setTrackOutputLevel(incomingPlayer, initialState.incomingVolume)
        setTrackOutputLevel(outgoingPlayer, initialState.outgoingVolume)

        val temp = currentPlayer
        currentPlayer = incomingPlayer
        nextPlayer = temp
        currentIndex++
        _currentSong.value = nextSong

        val fadeIntervalMs = 100L
        val totalSteps = ((durationSec * 1000) / fadeIntervalMs).toInt()
        var currentStep = 0

        fadeRunnable = object : Runnable {
            override fun run() {
                currentStep++
                val ratio = currentStep.toDouble() / totalSteps.toDouble()
                val incomingTimeSec = incomingPlayer.currentPosition.toDouble() / 1000.0
                val effectMultiplier = getCrossfadeEffectMultiplier(incomingTimeSec)
                val state = getCrossfadeState(ratio, BASE_VOL.toDouble(), breathRatio, dynamicProfile, effectMultiplier)

                setTrackOutputLevel(outgoingPlayer, state.outgoingVolume)
                setTrackOutputLevel(incomingPlayer, state.incomingVolume)

                if (currentStep >= totalSteps) {
                    finishCrossfade(outgoingPlayer, incomingPlayer)
                } else {
                    handler.postDelayed(this, fadeIntervalMs)
                }
            }
        }
        handler.post(fadeRunnable!!)
    }

    private fun finishCrossfade(outgoingPlayer: ExoPlayer, incomingPlayer: ExoPlayer) {
        outgoingPlayer.stop()
        outgoingPlayer.clearMediaItems()
        setTrackOutputLevel(outgoingPlayer, 0.0)
        setTrackOutputLevel(incomingPlayer, BASE_VOL.toDouble())

        _isTransitioning.value = false
        fadeRunnable = null
    }

    private fun stopCrossfade() {
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null
        _isTransitioning.value = false
    }

    private fun setTrackOutputLevel(player: ExoPlayer, level: Double) {
        val safeLevel = min(1.0, max(0.0, level)).toFloat()
        player.volume = safeLevel
    }

    // --- Mix Parameter Helpers (aligned with original author) ---

    private fun getMixLeadSeconds(song: Song): Double {
        val lead = if (song.mixLeadSec > 0) song.mixLeadSec else CROSSFADE_SEC
        return min(10.2, max(7.4, lead))
    }

    private fun getMixBreathSeconds(song: Song): Double {
        val breath = if (song.mixBreathSec > 0) song.mixBreathSec else 2.0
        return min(3.4, max(1.4, breath))
    }

    private fun getInvalidTailSeconds(song: Song): Double {
        val invalid = if (song.invalidTailSec > 0) song.invalidTailSec else 0.0
        return min(40.0, max(0.0, invalid))
    }

    private fun getMixEntrySeconds(song: Song): Double {
        val dynamicEntry = if (song.mixEntrySec > 0) song.mixEntrySec else null
        return if (dynamicEntry != null && dynamicEntry.isFinite()) {
            min(44.0, max(4.0, dynamicEntry))
        } else {
            min(44.0, max(4.0, MIX_ENTRY_BEFORE_END_SEC + getInvalidTailSeconds(song)))
        }
    }

    private fun getEffectStartSeconds(song: Song): Double {
        val dynamicStart = if (song.mixEffectStartSec > 0) song.mixEffectStartSec else null
        return if (dynamicStart != null && dynamicStart.isFinite()) {
            min(50.0, max(8.0, dynamicStart))
        } else {
            min(50.0, max(8.0, EFFECT_START_BEFORE_END_SEC + getInvalidTailSeconds(song)))
        }
    }

    private fun getAdaptiveMixEntrySeconds(outgoing: Song, incoming: Song): Double {
        val baseEntry = getMixEntrySeconds(outgoing)
        if (outgoing.durationSec <= 0 || incoming.durationSec <= 0) return baseEntry

        val outgoingEndBpm = if (outgoing.endBpm > 0) outgoing.endBpm else outgoing.bpm.coerceAtLeast(120.0)
        val incomingStartBpm = if (incoming.startBpm > 0) incoming.startBpm else incoming.bpm.coerceAtLeast(120.0)
        val bpmGap = kotlin.math.abs(incomingStartBpm - outgoingEndBpm)
        val bpmDifficulty = min(1.0, bpmGap / 36.0)

        val outgoingEnergy = max(0.008, outgoing.endDynamicEnergy.takeIf { it > 0 }
            ?: outgoing.end10sEnergy.takeIf { it > 0 }
            ?: outgoing.endEnergy.takeIf { it > 0 }
            ?: outgoing.energy.coerceAtLeast(0.06))

        val incomingEnergy = max(0.008, incoming.startDynamicEnergy.takeIf { it > 0 }
            ?: incoming.start10sEnergy.takeIf { it > 0 }
            ?: incoming.startEnergy.takeIf { it > 0 }
            ?: incoming.energy.coerceAtLeast(0.06))

        val loudnessDiffDb = kotlin.math.abs(20.0 * kotlin.math.log10(incomingEnergy / outgoingEnergy))
        val loudnessDifficulty = min(1.0, loudnessDiffDb / 8.5)

        val endActivityRatio = outgoing.endActivityRatio.coerceIn(0.0, 1.0)
        val tailWeakDifficulty = min(1.0, max(0.0, (0.55 - endActivityRatio) / 0.55))

        val incomingBaseEnergy = max(0.008, incoming.energy.coerceAtLeast(0.06))
        val incomingIntroEnergy = max(0.008, incoming.startDynamicEnergy.takeIf { it > 0 }
            ?: incoming.start10sEnergy.takeIf { it > 0 }
            ?: incoming.startEnergy.takeIf { it > 0 }
            ?: incomingBaseEnergy)

        val introEnergyRatio = incomingIntroEnergy / incomingBaseEnergy
        val introEnergyProtect = min(1.0, max(0.0, (introEnergyRatio - 0.9) / 0.9))
        val introBrightnessProtect = min(1.0, max(0.0, incoming.brightnessNorm))
        val introProtect = introEnergyProtect * 0.65 + introBrightnessProtect * 0.35

        val bridgeNeed = bpmDifficulty * 0.42 + loudnessDifficulty * 0.38 + tailWeakDifficulty * 0.2
        val adaptiveOffset = bridgeNeed * 1.48 - introProtect * 2.25 - 1.2
        val effectStartCap = max(2.8, getEffectStartSeconds(outgoing) - 0.9)
        return min(44.0, min(effectStartCap, max(2.8, baseEntry + adaptiveOffset)))
    }

    // --- Dynamic Profile (aligned with original author) ---

    private fun getTransitionDynamicProfile(outgoing: Song, incoming: Song): Map<String, Double> {
        val outgoingEnergy = max(0.008, outgoing.endDynamicEnergy.takeIf { it > 0 }
            ?: outgoing.end10sEnergy.takeIf { it > 0 }
            ?: outgoing.endEnergy.takeIf { it > 0 }
            ?: outgoing.energy.coerceAtLeast(0.06))

        val incomingEnergy = max(0.008, incoming.startDynamicEnergy.takeIf { it > 0 }
            ?: incoming.start10sEnergy.takeIf { it > 0 }
            ?: incoming.startEnergy.takeIf { it > 0 }
            ?: incoming.energy.coerceAtLeast(0.06))

        val loudnessDiffDb = 20.0 * kotlin.math.log10(incomingEnergy / outgoingEnergy)
        val baseFxIntensity = getFxIntensityFromLoudnessDiff(loudnessDiffDb)

        return when {
            loudnessDiffDb >= 1.5 -> {
                val attenuationDb = min(10.0, (loudnessDiffDb - 1.5) * 0.92)
                val incomingStartGain = max(0.52, min(0.9, 10.0.pow(-attenuationDb / 20.0)))
                val incomingEndGain = max(incomingStartGain, 0.93)
                mapOf(
                    "incomingStartGain" to incomingStartGain,
                    "incomingEndGain" to incomingEndGain,
                    "outgoingShape" to 1.07,
                    "incomingMaxGain" to 0.92,
                    "totalMixCap" to 0.98,
                    "fxIntensity" to min(1.4, baseFxIntensity * 1.06)
                )
            }
            loudnessDiffDb <= -1.5 -> {
                val boost = min(0.08, (-1.5 - loudnessDiffDb) * 0.012)
                mapOf(
                    "incomingStartGain" to (0.88 + boost * 0.5),
                    "incomingEndGain" to (0.96 + boost),
                    "outgoingShape" to 1.03,
                    "incomingMaxGain" to 0.98,
                    "totalMixCap" to 1.04,
                    "fxIntensity" to min(1.4, baseFxIntensity * 1.05)
                )
            }
            else -> {
                val neutralTrim = min(0.05, kotlin.math.abs(loudnessDiffDb) * 0.012)
                mapOf(
                    "incomingStartGain" to (0.84 - neutralTrim * 0.35),
                    "incomingEndGain" to (0.93 - neutralTrim),
                    "outgoingShape" to 1.05,
                    "incomingMaxGain" to 0.95,
                    "totalMixCap" to 1.0,
                    "fxIntensity" to max(0.74, baseFxIntensity * 0.92)
                )
            }
        }
    }

    // --- Crossfade State Math (aligned with original author) ---

    private fun getFxIntensityFromLoudnessDiff(loudnessDiffDb: Double): Double {
        val normalized = min(1.0, kotlin.math.abs(loudnessDiffDb) / 8.5)
        return 0.78 + normalized * 0.54
    }

    private fun getCrossfadeEffectMultiplier(incomingTimeSec: Double): Double {
        val safe = max(0.0, incomingTimeSec)
        if (safe < EFFECT_RELEASE_START_INCOMING_SEC) return 1.0
        if (safe >= EFFECT_RELEASE_END_INCOMING_SEC) return EFFECT_MIN_MULTIPLIER
        val releaseRatio = (safe - EFFECT_RELEASE_START_INCOMING_SEC) /
                (EFFECT_RELEASE_END_INCOMING_SEC - EFFECT_RELEASE_START_INCOMING_SEC)
        val smoothRelease = easeInOutCubic(releaseRatio)
        val softTailRelease = 1.0 - (1.0 - releaseRatio).pow(2.6)
        val releaseCurve = smoothRelease * 0.45 + softTailRelease * 0.55
        return 1.0 - (1.0 - EFFECT_MIN_MULTIPLIER) * releaseCurve
    }

    private fun getCrossfadeState(
        ratio: Double,
        baseVol: Double,
        breathRatio: Double = 0.14,
        dynamicProfile: Map<String, Double>? = null,
        effectMultiplier: Double = 1.0
    ): CrossfadeState {
        val safeRatio = min(1.0, max(0.0, ratio))
        val safeBreath = min(0.46, max(0.2, breathRatio))
        val inRatio = if (safeRatio <= safeBreath) 0.0 else (safeRatio - safeBreath) / (1.0 - safeBreath)
        val safeEffect = min(1.0, max(0.0, effectMultiplier))
        val entryEnhance = easeInOutCubic(min(1.0, safeRatio / EFFECT_ENTRY_SMOOTH_RATIO))

        val fadeOutCore = cos(safeRatio * 0.5 * Math.PI)
        val fadeOutDuck = 1.0 - (0.14 * safeRatio) - (0.12 * safeRatio.pow(2.2))
        val outgoingDepthRaw = min(1.0, 0.28 + safeRatio.pow(1.16))
        val incomingDepthLinear = max(0.12, 0.84 - 0.68 * inRatio)
        val incomingDepthCurve = max(0.12, 0.84 - 0.72 * inRatio.pow(0.68))
        val incomingDepthRaw = incomingDepthLinear * 0.42 + incomingDepthCurve * 0.58
        val outgoingReverbRaw = 0.1 + safeRatio.pow(1.2) * 0.26
        val incomingReverbRaw = max(0.1, 0.3 - inRatio * 0.17)

        val profile = dynamicProfile ?: mapOf(
            "incomingStartGain" to 0.84,
            "incomingEndGain" to 0.94,
            "outgoingShape" to 1.05,
            "incomingMaxGain" to 0.94,
            "totalMixCap" to 1.0,
            "fxIntensity" to 0.9
        )

        val fxIntensity = min(1.4, max(0.72, profile["fxIntensity"] ?: 0.9))
        val fxDepthScale = 0.72 + fxIntensity * 0.42
        val fxReverbScale = 0.7 + fxIntensity * 0.5
        val depthEntryFloorCurve = entryEnhance.pow(1.28)

        val outgoingDepthShaped = outgoingDepthRaw * 0.82 + (0.74 * depthEntryFloorCurve) * 0.18
        val incomingDepthShaped = incomingDepthRaw * 0.84 + (0.76 * depthEntryFloorCurve) * 0.16
        val outgoingDepthTarget = min(1.0, outgoingDepthShaped * safeEffect * fxDepthScale)
        val incomingDepthTarget = min(1.0, incomingDepthShaped * safeEffect * fxDepthScale)
        val outgoingDepth = min(1.0, outgoingDepthTarget * entryEnhance)
        val incomingDepth = min(1.0, incomingDepthTarget * entryEnhance)

        val outgoingReverbTarget = min(0.4, max(outgoingReverbRaw, 0.3 * entryEnhance) * safeEffect * fxReverbScale)
        val incomingReverbTarget = min(0.4, max(incomingReverbRaw, 0.3 * entryEnhance) * safeEffect * fxReverbScale)
        val outgoingReverb = min(0.36, outgoingReverbTarget * entryEnhance)
        val incomingReverb = min(0.36, incomingReverbTarget * entryEnhance)

        val incomingStartGain = profile["incomingStartGain"] ?: 0.84
        val incomingEndGain = profile["incomingEndGain"] ?: 0.94
        val outgoingShape = max(0.8, profile["outgoingShape"] ?: 1.05)
        val incomingGain = incomingStartGain + (incomingEndGain - incomingStartGain) * inRatio.pow(0.9)
        val shapedOutgoing = (max(0.0, fadeOutCore * max(0.0, fadeOutDuck))).pow(outgoingShape)
        val outgoingVolumeRaw = min(1.0, baseVol * shapedOutgoing)
        val incomingVolumeRaw = min(1.0, baseVol * incomingGain)
        val incomingMaxGain = profile["incomingMaxGain"] ?: 0.94
        val totalMixCap = profile["totalMixCap"] ?: 1.0
        val incomingMaxVolume = min(1.0, baseVol * min(1.05, max(0.72, incomingMaxGain)))
        val totalMixCapVolume = min(1.0, baseVol * min(1.25, max(0.84, totalMixCap)))
        val outgoingVolume = outgoingVolumeRaw
        val incomingVolumeCapped = min(incomingVolumeRaw, incomingMaxVolume)
        val incomingVolume = max(0.0, min(incomingVolumeCapped, totalMixCapVolume - outgoingVolume))

        return CrossfadeState(
            outgoingVolume = outgoingVolume,
            incomingVolume = incomingVolume,
            outgoingDepth = outgoingDepth,
            incomingDepth = incomingDepth,
            outgoingReverb = outgoingReverb,
            incomingReverb = incomingReverb
        )
    }

    // --- Math Helpers ---

    private fun easeInOutCubic(value: Double): Double {
        val safe = min(1.0, max(0.0, value))
        return if (safe < 0.5) {
            4.0 * safe.pow(3)
        } else {
            1.0 - ((-2.0 * safe + 2.0).pow(3)) / 2.0
        }
    }

    // --- URI Resolution ---

    private fun resolveUri(path: String): Uri {
        return when {
            path.startsWith("content://") || path.startsWith("file://") -> Uri.parse(path)
            File(path).exists() -> Uri.fromFile(File(path))
            else -> Uri.parse(path)
        }
    }

    // --- Release ---

    fun release() {
        stopProgressTracking()
        stopCrossfade()
        playerA.release()
        playerB.release()
        handler.removeCallbacksAndMessages(null)
    }

    data class CrossfadeState(
        val outgoingVolume: Double,
        val incomingVolume: Double,
        val outgoingDepth: Double,
        val incomingDepth: Double,
        val outgoingReverb: Double,
        val incomingReverb: Double
    )
}
