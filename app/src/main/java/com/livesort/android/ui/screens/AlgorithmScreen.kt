package com.livesort.android.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AlgorithmScreen(
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "编排的艺术",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            ),
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "统一展示 LiveSort 如何组织节奏、情绪与平滑过渡",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        AlgorithmSection(title = "设计哲学") {
            Text(
                text = "LiveSort 的诞生源于一个简单的想法：如何让杂乱无章的日常歌单，带来如同亲临演唱会现场般的沉浸体验？\n\n一场优秀的演唱会，其歌单绝不是随机播放的。它讲究“一收一发”，用抓耳但渐进的歌曲开场，在中间穿插节奏与抒情，避免观众因持续的高潮而情绪疲劳，也不会因为一直抒情而感到沉闷，最终以温暖的歌曲完美收尾。LiveSort 试图通过音频特征分析与算法编排，在本地重现这种情绪的起伏。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp
            )
        }

        AlgorithmSection(title = "优秀兴趣曲线") {
            Text(
                text = "万物皆有递归性。一首单曲的情绪设计（Hook开头 → 主歌铺垫 → 副歌小高潮 → 间奏过渡 → 最终高潮 → 收尾）同样适用于整场演唱会的宏观编排。\n\n我们引入了著名的“优秀兴趣曲线 (The Interest Curve)”。在游戏设计和叙事学中，这条曲线被认为是最能抓住受众注意力的节奏模式。应用在歌单中，它的关键节点包括：",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            BulletPoint("Hook (引子)：开场立刻抓住注意力。")
            BulletPoint("Valley (谷底)：情绪回落，为后续积蓄能量。")
            BulletPoint("Rising Peaks (波峰与波谷)：交替上升的节奏，每次高潮都比上一次更强烈。")
            BulletPoint("Climax (高潮)：全场情绪的最高点。")
            BulletPoint("Outro (收尾)：迅速而温暖地结束，留下余韵。")
        }

        AlgorithmSection(title = "音频特征提取") {
            Text(
                text = "为了将歌曲匹配到兴趣曲线上，LiveSort 采用本地分析链路：通过 MediaCodec 在设备端解码并提取音频特征。我们不仅提取整首歌曲的平均特征，还专门提取首尾15秒的特征，以确保歌曲之间的平滑过渡。\n\n当前版本不依赖云端后端。核心特征包括：",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            BulletPoint("BPM (节拍率)：决定歌曲的物理速度。")
            BulletPoint("Energy (RMS能量)：衡量歌曲的响度和饱满度。")
            BulletPoint("Brightness (频谱质心)：反映声音的明亮程度（低沉还是高亢）。")
        }

        AlgorithmSection(title = "排序算法") {
            Text(
                text = "LiveSort 的核心是一个贪心优化算法。对于播放列表中的每一个位置，算法会计算剩余所有候选歌曲的“代价函数 (Cost Function)”，并选择代价最小的歌曲填入该位置。\n\n代价函数综合考虑了三个权重因素：",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            NumberedPoint("1", "情绪贴合度 (最高权重)：候选歌曲的情绪得分与“兴趣曲线”当前位置理想得分的绝对差值。")
            NumberedPoint("2", "BPM 衔接度：上一首歌结尾的 BPM 与候选歌曲开头的 BPM 的比例。完美衔接（1:1）或倍速衔接（1:2，例如 60 BPM 接 120 BPM）会获得极低的惩罚代价。")
            NumberedPoint("3", "能量平滑度：上一首歌结尾的能量与候选歌曲开头的能量差异，避免听感上的突兀。")
        }

        AlgorithmSection(title = "无感过渡") {
            Text(
                text = "无感过渡的目标很简单：让两首歌的交接更自然，不突兀。系统会在前一首快结束时，提前把下一首轻柔接入，整个过渡大约 10 秒左右。\n\n具体实现包括：",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            BulletPoint("自动找时机：系统会判断前一首尾段状态，选择更合适的切入点，不会生硬“硬切”。")
            BulletPoint("音量自动平衡：自动控制两首歌的重叠音量，避免突然变大声或听起来太吵。")
            BulletPoint("效果平滑过渡：EQ 和混响是渐进变化，不会一瞬间把音色改得很夸张。")
            BulletPoint("保留下一首开头细节：尽量让下一首的前奏清晰可听，不被前一首尾声盖住。")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "你可以把它理解为一个“更聪明的淡入淡出”：该提前就提前，该轻一点就轻一点，目标就是让整段听感连贯、舒服。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Crossfade 曲线示意图
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "过渡参数变化示意（10秒窗口）",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    CrossfadeChart(modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ChartLegend("上一首音量", Color(0xFF333333), isDashed = false)
                        ChartLegend("下一首音量", Color(0xFF999999), isDashed = false)
                        ChartLegend("下潜深度", Color(0xFF4A90D9), isDashed = true)
                        ChartLegend("混响湿度", Color(0xFFE74C3C), isDashed = true)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun AlgorithmSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun BulletPoint(text: String) {
    RowWithIcon(icon = "•", text = text)
}

@Composable
private fun NumberedPoint(number: String, text: String) {
    RowWithIcon(icon = number, text = text)
}

@Composable
private fun RowWithIcon(icon: String, text: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = icon,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ChartLegend(label: String, color: Color, isDashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val pathEffect = if (isDashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f) else null
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(2.5.dp)
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
                drawLine(
                    color = color,
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = 2.5.dp.toPx(),
                    pathEffect = pathEffect,
                    cap = StrokeCap.Round
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CrossfadeChart(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val width = size.width
            val height = size.height
            val padLeft = 28.dp.toPx()
            val padRight = 12.dp.toPx()
            val padTop = 8.dp.toPx()
            val padBottom = 24.dp.toPx()
            val chartW = width - padLeft - padRight
            val chartH = height - padTop - padBottom

            // 背景
            drawRect(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))

            // 网格线
            val gridY = listOf(0.25f, 0.5f, 0.75f)
            for (gy in gridY) {
                val y = padTop + chartH * (1 - gy)
                drawLine(
                    color = Color.Gray.copy(alpha = 0.15f),
                    start = Offset(padLeft, y),
                    end = Offset(padLeft + chartW, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            val steps = 100
            fun xOf(t: Float) = padLeft + t * chartW
            fun yOf(v: Float) = padTop + chartH * (1 - v)

            // 上一首音量：1.0 -> 0.0（S-curve）
            val prevVol = (0..steps).map { i ->
                val t = i / steps.toFloat()
                val v = 1f - (t * t * (3 - 2 * t)) // smoothstep
                Offset(xOf(t), yOf(v))
            }
            for (i in 0 until prevVol.size - 1) {
                drawLine(
                    color = Color(0xFF333333),
                    start = prevVol[i],
                    end = prevVol[i + 1],
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // 下一首音量：0.0 -> 1.0（delayed S-curve）
            val nextVol = (0..steps).map { i ->
                val t = i / steps.toFloat()
                val delay = 0.15f
                val v = if (t < delay) 0f else {
                    val tt = (t - delay) / (1 - delay)
                    tt * tt * (3 - 2 * tt)
                }
                Offset(xOf(t), yOf(v))
            }
            for (i in 0 until nextVol.size - 1) {
                drawLine(
                    color = Color(0xFF999999),
                    start = nextVol[i],
                    end = nextVol[i + 1],
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // 下潜深度：0 -> peak -> 0（parabola）
            val dive = (0..steps).map { i ->
                val t = i / steps.toFloat()
                val v = 4 * t * (1 - t) * 0.7f
                Offset(xOf(t), yOf(v))
            }
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
            for (i in 0 until dive.size - 1) {
                drawLine(
                    color = Color(0xFF4A90D9),
                    start = dive[i],
                    end = dive[i + 1],
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = dashEffect
                )
            }

            // 混响湿度：0 -> peak -> 0（shifted parabola）
            val reverb = (0..steps).map { i ->
                val t = i / steps.toFloat()
                val v = 4 * t * (1 - t) * 0.5f
                Offset(xOf(t), yOf(v))
            }
            for (i in 0 until reverb.size - 1) {
                drawLine(
                    color = Color(0xFFE74C3C),
                    start = reverb[i],
                    end = reverb[i + 1],
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = dashEffect
                )
            }

            // X 轴标签
            val labelPaint = android.text.TextPaint().apply {
                color = android.graphics.Color.GRAY
                textSize = 10.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val times = listOf("0s" to 0f, "2.5s" to 0.25f, "5s" to 0.5f, "7.5s" to 0.75f, "10s" to 1f)
            for ((text, t) in times) {
                val x = xOf(t)
                drawContext.canvas.nativeCanvas.drawText(text, x, height - 6.dp.toPx(), labelPaint)
            }

            // Y 轴标签
            val yLabels = listOf("0" to 0f, "0.5" to 0.5f, "1.0" to 1f)
            labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
            for ((text, v) in yLabels) {
                val y = yOf(v)
                drawContext.canvas.nativeCanvas.drawText(text, padLeft - 6.dp.toPx(), y + 4.dp.toPx(), labelPaint)
            }
        }
    }
}
