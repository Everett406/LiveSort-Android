package com.livesort.android.viewmodel

import com.livesort.android.audio.AudioAnalyzer
import com.livesort.android.model.Song
import com.livesort.android.sorting.PlaylistSorter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 歌单 ViewModel（纯 Kotlin，平台无关）
 *
 * 管理歌曲列表、分析状态、排序结果
 */
class PlaylistViewModel(
    private val audioAnalyzer: AudioAnalyzer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _sortedSongs = MutableStateFlow<List<Song>>(emptyList())
    val sortedSongs: StateFlow<List<Song>> = _sortedSongs.asStateFlow()

    private val _idealCurve = MutableStateFlow<List<Double>>(emptyList())
    val idealCurve: StateFlow<List<Double>> = _idealCurve.asStateFlow()

    private val _actualCurve = MutableStateFlow<List<Double>>(emptyList())
    val actualCurve: StateFlow<List<Double>> = _actualCurve.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _currentPlayingIndex = MutableStateFlow<Int>(-1)
    val currentPlayingIndex: StateFlow<Int> = _currentPlayingIndex.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * 添加歌曲并触发分析
     */
    fun addSongs(newSongs: List<Song>) {
        val updated = _songs.value + newSongs
        _songs.value = updated
        analyzeAll()
    }

    /**
     * 移除歌曲
     */
    fun removeSong(songId: Int) {
        val updated = _songs.value.filter { it.id != songId }
        _songs.value = updated
        reSort()
    }

    /**
     * 手动调整歌曲顺序（用户拖拽后）
     */
    fun reorderSortedSongs(fromIndex: Int, toIndex: Int) {
        val current = _sortedSongs.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            _sortedSongs.value = current
            _actualCurve.value = current.map { it.emotionScore }
        }
    }

    /**
     * 分析所有未分析的歌曲
     */
    fun analyzeAll() {
        scope.launch {
            val toAnalyze = _songs.value.filter { !it.isAnalyzed && !it.isLoading }
            if (toAnalyze.isEmpty()) {
                reSort()
                return@launch
            }

            _isAnalyzing.value = true
            _errorMessage.value = null

            // 标记为分析中
            val loadingMap = toAnalyze.associateBy { it.id }
            _songs.value = _songs.value.map { song ->
                if (song.id in loadingMap) song.copy(isLoading = true) else song
            }

            // 串行分析（避免内存峰值）
            var failedCount = 0
            for (song in toAnalyze) {
                val features = try {
                    withContext(Dispatchers.Default) {
                        audioAnalyzer.analyze(song.filename)
                    }
                } catch (e: Throwable) {
                    Log.e("PlaylistViewModel", "分析崩溃: ${song.title}", e)
                    failedCount++
                    null
                }

                val updatedSong = if (features != null) {
                    song.copy(
                        durationSec = features.durationSec,
                        bpm = features.bpm,
                        energy = features.energy,
                        brightness = features.brightness,
                        startBpm = features.startBpm,
                        startEnergy = features.startEnergy,
                        start10sEnergy = features.start10sEnergy,
                        startDynamicEnergy = features.startDynamicEnergy,
                        endBpm = features.endBpm,
                        endEnergy = features.endEnergy,
                        end10sEnergy = features.end10sEnergy,
                        endDynamicEnergy = features.endDynamicEnergy,
                        dynamicWindowSec = features.dynamicWindowSec,
                        tailSilenceSec = features.tailSilenceSec,
                        tailScanWindowSec = features.tailScanWindowSec,
                        invalidTailSec = features.invalidTailSec,
                        endActivityRatio = features.endActivityRatio,
                        mixLeadSec = features.mixLeadSec,
                        mixBreathSec = features.mixBreathSec,
                        mixEffectStartSec = features.mixEffectStartSec,
                        mixEntrySec = features.mixEntrySec,
                        isAnalyzed = true,
                        isLoading = false
                    )
                } else {
                    song.copy(isLoading = false)
                }

                // 实时更新列表
                _songs.value = _songs.value.map { s ->
                    if (s.id == updatedSong.id) updatedSong else s
                }
            }

            _isAnalyzing.value = false
            if (failedCount > 0) {
                _errorMessage.value = "$failedCount 首歌曲分析失败，请检查文件格式是否支持"
            }
            reSort()
        }
    }

    /**
     * 重新排序
     */
    fun reSort() {
        val analyzedSongs = _songs.value.filter { it.isAnalyzed }
        if (analyzedSongs.size < 2) {
            _sortedSongs.value = analyzedSongs
            _actualCurve.value = emptyList()
            _idealCurve.value = emptyList()
            return
        }

        val normalized = PlaylistSorter.normalizeAndScore(analyzedSongs)
        val result = PlaylistSorter.sort(normalized)

        _sortedSongs.value = result.sortedPlaylist
        _actualCurve.value = result.actualCurve
        _idealCurve.value = result.idealCurve

        // Auto-select first song so PlayerBar shows up
        if (result.sortedPlaylist.isNotEmpty() && _currentPlayingIndex.value < 0) {
            _currentPlayingIndex.value = 0
        }
    }

    /**
     * 设置当前播放索引
     */
    fun setPlayingIndex(index: Int) {
        _currentPlayingIndex.value = index
    }

    fun clear() {
        _songs.value = emptyList()
        _sortedSongs.value = emptyList()
        _idealCurve.value = emptyList()
        _actualCurve.value = emptyList()
        _currentPlayingIndex.value = -1
        _errorMessage.value = null
    }

    fun consumeError() {
        _errorMessage.value = null
    }

    fun postError(message: String) {
        _errorMessage.value = message
    }

    fun dispose() {
        scope.cancel()
    }
}
