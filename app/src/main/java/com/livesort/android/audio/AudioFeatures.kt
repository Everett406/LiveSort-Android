package com.livesort.android.audio

/**
 * 音频特征提取结果
 * 完全对齐原作者 Python 实现 (audio_analyzer.py)
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

    // 过渡参数（对齐 Python）
    val dynamicWindowSec: Double = 10.0,
    val tailSilenceSec: Double = 0.0,
    val tailScanWindowSec: Double = 40.0,
    val invalidTailSec: Double = 0.0,
    val endActivityRatio: Double = 1.0,
    val mixLeadSec: Double = 9.1,
    val mixBreathSec: Double = 1.75,
    val mixEffectStartSec: Double = 10.0,
    val mixEntrySec: Double = 4.0
)
