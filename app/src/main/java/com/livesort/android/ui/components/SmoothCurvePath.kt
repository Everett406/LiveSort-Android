package com.livesort.android.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.max
import kotlin.math.min

/**
 * 将一系列点转换为平滑的三次贝塞尔曲线路径（Catmull-Rom 样条）
 *
 * 对齐原作者 Chart.js 的平滑曲线效果
 */
fun createSmoothPath(points: List<Offset>): Path {
    if (points.isEmpty()) return Path()
    if (points.size == 1) {
        return Path().apply { moveTo(points[0].x, points[0].y) }
    }
    if (points.size == 2) {
        return Path().apply {
            moveTo(points[0].x, points[0].y)
            lineTo(points[1].x, points[1].y)
        }
    }

    val path = Path()
    path.moveTo(points[0].x, points[0].y)

    for (i in 0 until points.size - 1) {
        val p0 = points[max(0, i - 1)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[min(points.size - 1, i + 2)]

        // Catmull-Rom to cubic Bezier control points (tension = 1/6)
        val tension = 0.1667f
        val cp1 = Offset(
            p1.x + (p2.x - p0.x) * tension,
            p1.y + (p2.y - p0.y) * tension
        )
        val cp2 = Offset(
            p2.x - (p3.x - p1.x) * tension,
            p2.y - (p3.y - p1.y) * tension
        )
        path.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, p2.x, p2.y)
    }
    return path
}
