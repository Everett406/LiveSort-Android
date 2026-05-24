package com.livesort.android.audio

/**
 * 音频特征提取结果
 */
data class AudioFeatures(
    val durationSec: Double = 0.0,
    val bpm: Double = 0.0,
    val energy: Double = 0.0,
    val brightness: Double = 0.0,

    val startBpm: Double = 0.0,
    val startEnergy: Double = 0.0,
    val start10sEnergy: Double = 0.0,
    val startDynamicEnergy: Double = 0.0,

    val endBpm: Double = 0.0,
    val endEnergy: Double = 0.0,
    val end10sEnergy: Double = 0.0,
    val endDynamicEnergy: Double = 0.0,

    val mixLeadSec: Double = 9.0,
    val mixBreathSec: Double = 1.75,
    val mixEffectStartSec: Double = 10.0,
    val mixEntrySec: Double = 8.0
)
