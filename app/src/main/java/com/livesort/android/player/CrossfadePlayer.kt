package com.livesort.android.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.livesort.android.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * 无感过渡播放器
 *
 * 使用双 ExoPlayer 实例实现 Crossfade：
 * - 当前歌曲即将结束时，提前启动下一首
 * - 重叠区域内，A 播放器淡出，B 播放器淡入
 * - 过渡完成后切换主次播放器
 */
class CrossfadePlayer(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    private var progressJob: Job? = null
    private var transitionJob: Job? = null

    init {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _durationMs.value = currentPlayer.duration.coerceAtLeast(0)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) startProgressTracking()
                else stopProgressTracking()
            }
        }
        playerA.addListener(listener)
        playerB.addListener(listener)
    }

    /**
     * 设置播放列表
     */
    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        playlist = songs
        if (startIndex in songs.indices) {
            playAt(startIndex)
        }
    }

    /**
     * 播放指定索引
     */
    fun playAt(index: Int) {
        if (index !in playlist.indices) return
        currentIndex = index
        val song = playlist[index]
        _currentSong.value = song

        // 停止任何进行中的过渡
        transitionJob?.cancel()
        _isTransitioning.value = false

        // 重置播放器
        currentPlayer.stop()
        currentPlayer.clearMediaItems()
        nextPlayer.stop()
        nextPlayer.clearMediaItems()

        // 加载当前歌曲
        val uri = Uri.parse(song.filename)
        currentPlayer.setMediaItem(MediaItem.fromUri(uri))
        currentPlayer.prepare()
        currentPlayer.play()

        _isPlaying.value = true
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

    /**
     * 启动进度追踪和自动过渡检测
     */
    private fun startProgressTracking() {
        stopProgressTracking()
        progressJob = scope.launch {
            while (true) {
                val pos = currentPlayer.currentPosition.coerceAtLeast(0)
                val dur = currentPlayer.duration.coerceAtLeast(1)
                _currentPositionMs.value = pos
                _durationMs.value = dur
                _progress.value = pos.toFloat() / dur.toFloat()

                // 检测是否需要启动过渡
                if (!_isTransitioning.value && currentIndex + 1 < playlist.size) {
                    val nextSong = playlist[currentIndex + 1]
                    val mixEntryMs = (nextSong.mixEntrySec * 1000).toLong()
                    val transitionStartMs = dur - mixEntryMs

                    if (pos >= transitionStartMs && dur > mixEntryMs) {
                        startCrossfade(nextSong, mixEntryMs)
                    }
                }

                delay(100)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    /**
     * 启动 Crossfade 过渡
     */
    private fun startCrossfade(nextSong: Song, overlapMs: Long) {
        if (_isTransitioning.value) return
        _isTransitioning.value = true

        // 准备下一首
        val uri = Uri.parse(nextSong.filename)
        nextPlayer.setMediaItem(MediaItem.fromUri(uri))
        nextPlayer.volume = 0f
        nextPlayer.prepare()
        nextPlayer.play()

        val fadeSteps = 20
        val stepDurationMs = overlapMs / fadeSteps

        transitionJob = scope.launch {
            for (step in 0..fadeSteps) {
                val ratio = step.toFloat() / fadeSteps.toFloat()
                currentPlayer.volume = 1f - ratio
                nextPlayer.volume = ratio
                delay(stepDurationMs)
            }

            // 过渡完成，切换主次
            currentPlayer.stop()
            currentPlayer.clearMediaItems()
            currentPlayer.volume = 1f

            // 交换引用
            val temp = currentPlayer
            currentPlayer = nextPlayer
            nextPlayer = temp

            currentIndex++
            _currentSong.value = nextSong
            _isTransitioning.value = false

            // 继续追踪新 currentPlayer 的进度
            startProgressTracking()
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        stopProgressTracking()
        transitionJob?.cancel()
        playerA.release()
        playerB.release()
        scope.cancel()
    }
}
