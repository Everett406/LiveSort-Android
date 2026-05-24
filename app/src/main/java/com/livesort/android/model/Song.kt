package com.livesort.android.model

/**
 * 歌曲数据模型，包含音频特征与元数据
 */
data class Song(
    val id: Int = 0,
    val filename: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSec: Double = 0.0,
    val fileSizeBytes: Long = 0L,

    // 整体特征
    val bpm: Double = 0.0,
    val energy: Double = 0.0,
    val brightness: Double = 0.0,

    // 开头特征
    val startBpm: Double = 0.0,
    val startEnergy: Double = 0.0,
    val start10sEnergy: Double = 0.0,
    val startDynamicEnergy: Double = 0.0,

    // 结尾特征
    val endBpm: Double = 0.0,
    val endEnergy: Double = 0.0,
    val end10sEnergy: Double = 0.0,
    val endDynamicEnergy: Double = 0.0,

    // 过渡参数
    val mixLeadSec: Double = 9.0,
    val mixBreathSec: Double = 1.75,
    val mixEffectStartSec: Double = 10.0,
    val mixEntrySec: Double = 8.0,

    // 归一化特征（分析后计算）
    val bpmNorm: Double = 0.0,
    val energyNorm: Double = 0.0,
    val brightnessNorm: Double = 0.0,
    val emotionScore: Double = 0.0,

    // UI 状态
    val coverPath: String? = null,
    val isAnalyzed: Boolean = false,
    val isLoading: Boolean = false
)
