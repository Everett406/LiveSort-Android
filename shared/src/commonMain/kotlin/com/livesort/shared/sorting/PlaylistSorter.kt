package com.livesort.shared.sorting

import com.livesort.shared.model.Song
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 歌单排序器
 *
 * 基于目标情绪曲线和前后歌曲的过渡（BPM与能量）进行贪心优化。
 * 对于歌单中的每一个位置，综合评估候选歌曲与目标曲线的贴合度，
 * 并同时考虑与上一首歌曲的 BPM、能量是否自然衔接。
 */
object PlaylistSorter {

    /**
     * 排序结果
     */
    data class SortResult(
        val sortedPlaylist: List<Song>,
        val actualCurve: List<Double>,
        val idealCurve: List<Double>
    )

    /**
     * 对歌曲列表进行排序
     *
     * @param songs 待排序的歌曲列表（需要已经计算好 emotionScore）
     * @return 排序结果，包含排序后的歌单、实际情绪曲线、理想情绪曲线
     */
    fun sort(songs: List<Song>): SortResult {
        if (songs.isEmpty()) {
            return SortResult(emptyList(), emptyList(), emptyList())
        }

        val numSongs = songs.size
        val idealScores = InterestCurve.generate(numSongs)

        val remaining = songs.toMutableList()
        val sorted = mutableListOf<Song>()

        // 第一首歌：选择最接近第一个理想情绪值的
        val firstSong = remaining.minByOrNull { song ->
            abs(song.emotionScore - idealScores[0])
        } ?: remaining.first()

        sorted.add(firstSong)
        remaining.remove(firstSong)

        // 后续歌曲：贪心选择 cost 最小的
        for (i in 1 until numSongs) {
            val targetEmotion = idealScores[i]
            val prevSong = sorted.last()

            val prevEndBpm = if (prevSong.endBpm > 0) prevSong.endBpm else prevSong.bpm
            val prevEndEnergy = if (prevSong.endEnergy > 0) prevSong.endEnergy else prevSong.energy

            var bestSong: Song? = null
            var bestCost = Double.POSITIVE_INFINITY

            for (candidate in remaining) {
                val candStartBpm = if (candidate.startBpm > 0) candidate.startBpm else candidate.bpm
                val candStartEnergy = if (candidate.startEnergy > 0) candidate.startEnergy else candidate.energy
                val candEmotion = candidate.emotionScore

                // 1. 情绪差异权重（最重要）
                val emotionDiff = abs(candEmotion - targetEmotion)

                // 2. BPM 差异：允许直接接，或者倍速/半速接（比如 60 接 120）
                val maxBpm = max(candStartBpm, prevEndBpm)
                val minBpm = max(min(candStartBpm, prevEndBpm), 1.0)
                val bpmRatio = maxBpm / minBpm
                val bpmDiff = min(abs(bpmRatio - 1.0), abs(bpmRatio - 2.0)) * 100.0

                // 3. 能量差异
                val energyDiff = abs(candStartEnergy - prevEndEnergy) * 50.0

                // 综合 Cost：情绪贴合曲线最重要，其次是 BPM 衔接，再是能量平滑
                val cost = emotionDiff * 1.5 + bpmDiff * 1.0 + energyDiff * 0.5

                if (cost < bestCost) {
                    bestCost = cost
                    bestSong = candidate
                }
            }

            val chosen = bestSong ?: remaining.first()
            sorted.add(chosen)
            remaining.remove(chosen)
        }

        val actualScores = sorted.map { it.emotionScore }
        return SortResult(sorted, actualScores, idealScores)
    }

    /**
     * 归一化歌曲特征，计算 emotionScore
     */
    fun normalizeAndScore(songs: List<Song>): List<Song> {
        if (songs.isEmpty()) return emptyList()

        val maxBpm = songs.maxOfOrNull { it.bpm }?.coerceAtLeast(1.0) ?: 1.0
        val maxEnergy = songs.maxOfOrNull { it.energy }?.coerceAtLeast(1.0) ?: 1.0
        val maxBrightness = songs.maxOfOrNull { it.brightness }?.coerceAtLeast(1.0) ?: 1.0

        return songs.map { song ->
            val bpmNorm = song.bpm / maxBpm
            val energyNorm = song.energy / maxEnergy
            val brightnessNorm = song.brightness / maxBrightness

            // 综合情绪值: 高 BPM 和高能量代表高昂的情绪
            val emotionScore = (bpmNorm * 0.4 + energyNorm * 0.4 + brightnessNorm * 0.2) * 100.0

            song.copy(
                bpmNorm = bpmNorm,
                energyNorm = energyNorm,
                brightnessNorm = brightnessNorm,
                emotionScore = emotionScore
            )
        }
    }
}
