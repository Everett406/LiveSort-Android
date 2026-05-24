package com.livesort.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.livesort.android.ui.theme.OrangePrimary
import com.livesort.android.ui.theme.OrangeSoft

/**
 * 情绪曲线图表
 *
 * 绘制理想曲线（虚线）与实际曲线（实线）的对比
 * 使用平滑的三次贝塞尔曲线（对齐原作者 Chart.js 效果）
 */
@Composable
fun EmotionChart(
    idealCurve: List<Double>,
    actualCurve: List<Double>,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(180.dp)
        .clip(RoundedCornerShape(20.dp))
) {
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val width = size.width
            val height = size.height
            val paddingHorizontal = 24.dp.toPx()
            val paddingVertical = 20.dp.toPx()

            val chartWidth = width - paddingHorizontal * 2
            val chartHeight = height - paddingVertical * 2

            // 背景
            drawRect(color = surfaceColor)

            if (idealCurve.isEmpty() || actualCurve.isEmpty()) return@Canvas

            val maxValue = 100.0

            // 计算点坐标
            fun point(index: Int, value: Double): Offset {
                val x = paddingHorizontal + (index.toFloat() / (idealCurve.size - 1).coerceAtLeast(1)) * chartWidth
                val y = paddingVertical + chartHeight - ((value / maxValue).toFloat() * chartHeight)
                return Offset(x, y)
            }

            val idealPoints = idealCurve.mapIndexed { i, v -> point(i, v) }
            val actualPoints = actualCurve.mapIndexed { i, v -> point(i, v) }

            // 绘制理想曲线（虚线）
            val idealPath = createSmoothPath(idealPoints)
            drawPath(
                path = idealPath,
                color = onSurfaceVariant.copy(alpha = 0.4f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )

            // 绘制实际曲线（实线 + 渐变填充）
            val actualPath = createSmoothPath(actualPoints)

            // 闭合路径用于填充
            val fillPath = Path().apply {
                addPath(actualPath)
                val last = actualPoints.last()
                val first = actualPoints.first()
                lineTo(last.x, paddingVertical + chartHeight)
                lineTo(first.x, paddingVertical + chartHeight)
                close()
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        OrangePrimary.copy(alpha = 0.15f),
                        OrangePrimary.copy(alpha = 0.02f)
                    ),
                    startY = paddingVertical,
                    endY = paddingVertical + chartHeight
                )
            )

            drawPath(
                path = actualPath,
                color = OrangePrimary,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )

            // 绘制数据点
            actualPoints.forEach { p ->
                drawCircle(
                    color = OrangePrimary,
                    radius = 4.dp.toPx(),
                    center = p
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.dp.toPx(),
                    center = p
                )
            }
        }
    }
}
