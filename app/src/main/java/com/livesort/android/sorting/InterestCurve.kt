package com.livesort.android.sorting

/**
 * 生成理想的演唱会情绪曲线（Interest Curve）
 *
 * 基于经典的叙事节奏理论：
 * 开场 Hook -> 谷底 -> 逐步上升的多个波峰与波谷 -> 最终高潮(Climax) -> 迅速收尾
 */
object InterestCurve {

    // 时间节点 (0.0 - 1.0 比例)
    private val timelineNorm = listOf(0.0, 0.15, 0.22, 0.32, 0.40, 0.55, 0.65, 0.80, 1.0)

    // 设定的对应情绪值 (0.0 - 1.0 比例)
    private val emotions = listOf(0.1, 0.6, 0.25, 0.45, 0.35, 0.75, 0.4, 1.0, 0.05)

    /**
     * 生成理想情绪曲线，映射到 10-90 分数区间
     */
    fun generate(numSongs: Int): List<Double> {
        if (numSongs <= 0) return emptyList()
        if (numSongs == 1) return listOf(50.0)

        // 为每首歌生成对应的位置 (0 到 1)
        val x = List(numSongs) { index -> index.toDouble() / (numSongs - 1) }

        // 线性插值计算理想情绪值
        val idealScores = x.map { xi -> interpolate(xi, timelineNorm, emotions) }

        // 映射到 10-90 的分数区间
        return idealScores.map { score -> score * 80.0 + 10.0 }
    }

    /**
     * 分段线性插值
     */
    private fun interpolate(x: Double, xs: List<Double>, ys: List<Double>): Double {
        require(xs.size == ys.size) { "xs and ys must have same size" }
        require(xs.isNotEmpty()) { "xs must not be empty" }

        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()

        // 找到对应的区间
        for (i in 0 until xs.size - 1) {
            if (x >= xs[i] && x <= xs[i + 1]) {
                val t = if (xs[i + 1] == xs[i]) 0.0
                else (x - xs[i]) / (xs[i + 1] - xs[i])
                return ys[i] + t * (ys[i + 1] - ys[i])
            }
        }
        return ys.last()
    }

    /**
     * 获取某一段落的名称（用于 UI 展示）
     */
    fun getSectionName(positionRatio: Double, isChinese: Boolean = true): String {
        return when {
            positionRatio < 0.15 -> if (isChinese) "开场" else "Opening"
            positionRatio < 0.32 -> if (isChinese) "铺垫" else "Build-up"
            positionRatio < 0.45 -> if (isChinese) "回落" else "Fallback"
            positionRatio < 0.75 -> if (isChinese) "递进" else "Rising"
            positionRatio < 0.92 -> if (isChinese) "高潮" else "Climax"
            else -> if (isChinese) "收尾" else "Finale"
        }
    }
}
