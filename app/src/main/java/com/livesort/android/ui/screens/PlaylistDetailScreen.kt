package com.livesort.android.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livesort.android.model.Song
import com.livesort.android.sorting.InterestCurve
import com.livesort.android.ui.theme.OrangePrimary
import com.livesort.android.ui.theme.OrangeSoft

@Composable
fun PlaylistDetailScreen(
    sortedSongs: List<Song>,
    idealCurve: List<Double>,
    actualCurve: List<Double>,
    currentPlayingIndex: Int,
    onBack: () -> Unit,
    onSongClick: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "排序详情",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Large Emotion Chart
        LargeEmotionChart(
            idealCurve = idealCurve,
            actualCurve = actualCurve,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(20.dp))
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Section labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SectionLegend("理想曲线", MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), true)
            SectionLegend("实际曲线", OrangePrimary, false)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Song list
        Text(
            text = "曲目列表 (${sortedSongs.size}首)",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(sortedSongs) { index, song ->
                DetailSongCard(
                    index = index,
                    song = song,
                    sectionName = InterestCurve.getSectionName(
                        index.toDouble() / sortedSongs.size.coerceAtLeast(1),
                        true
                    ),
                    isPlaying = index == currentPlayingIndex,
                    onClick = { onSongClick(index) }
                )
            }
        }
    }
}

@Composable
private fun SectionLegend(label: String, color: Color, isDashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(20.dp)
                .height(if (isDashed) 2.dp else 3.dp)
                .background(color, RoundedCornerShape(1.dp))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LargeEmotionChart(
    idealCurve: List<Double>,
    actualCurve: List<Double>,
    modifier: Modifier = Modifier
) {
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val width = size.width
            val height = size.height
            val paddingHorizontal = 32.dp.toPx()
            val paddingVertical = 28.dp.toPx()

            val chartWidth = width - paddingHorizontal * 2
            val chartHeight = height - paddingVertical * 2

            // 背景
            drawRect(color = surfaceColor)

            if (idealCurve.isEmpty() || actualCurve.isEmpty()) return@Canvas

            val maxValue = 100.0

            // 绘制网格线
            val gridLines = listOf(0.2, 0.4, 0.6, 0.8)
            for (grid in gridLines) {
                val y = paddingVertical + chartHeight * (1 - grid).toFloat()
                drawLine(
                    color = onSurfaceVariant.copy(alpha = 0.08f),
                    start = Offset(paddingHorizontal, y),
                    end = Offset(paddingHorizontal + chartWidth, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // 绘制理想曲线（虚线）
            val idealPath = Path()
            idealCurve.forEachIndexed { index, value ->
                val x = paddingHorizontal + (index.toFloat() / (idealCurve.size - 1).coerceAtLeast(1)) * chartWidth
                val y = paddingVertical + chartHeight - ((value / maxValue).toFloat() * chartHeight)
                if (index == 0) idealPath.moveTo(x, y) else idealPath.lineTo(x, y)
            }
            drawPath(
                path = idealPath,
                color = onSurfaceVariant.copy(alpha = 0.5f),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )

            // 绘制实际曲线（实线 + 渐变填充）
            val actualPath = Path()
            actualCurve.forEachIndexed { index, value ->
                val x = paddingHorizontal + (index.toFloat() / (actualCurve.size - 1).coerceAtLeast(1)) * chartWidth
                val y = paddingVertical + chartHeight - ((value / maxValue).toFloat() * chartHeight)
                if (index == 0) actualPath.moveTo(x, y) else actualPath.lineTo(x, y)
            }

            // 闭合路径用于填充
            val fillPath = Path().apply {
                addPath(actualPath)
                lineTo(paddingHorizontal + chartWidth, paddingVertical + chartHeight)
                lineTo(paddingHorizontal, paddingVertical + chartHeight)
                close()
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        OrangePrimary.copy(alpha = 0.18f),
                        OrangePrimary.copy(alpha = 0.02f)
                    ),
                    startY = paddingVertical,
                    endY = paddingVertical + chartHeight
                )
            )

            drawPath(
                path = actualPath,
                color = OrangePrimary,
                style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
            )

            // 绘制数据点
            actualCurve.forEachIndexed { index, value ->
                val x = paddingHorizontal + (index.toFloat() / (actualCurve.size - 1).coerceAtLeast(1)) * chartWidth
                val y = paddingVertical + chartHeight - ((value / maxValue).toFloat() * chartHeight)
                drawCircle(
                    color = OrangePrimary,
                    radius = 5.dp.toPx(),
                    center = Offset(x, y)
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.5.dp.toPx(),
                    center = Offset(x, y)
                )
            }
        }
    }
}

@Composable
private fun DetailSongCard(
    index: Int,
    song: Song,
    sectionName: String,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Index badge
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isPlaying) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = "${index + 1}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title.ifEmpty { song.filename },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = sectionName,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "BPM ${song.bpm.toInt()}  ·  情绪 ${song.emotionScore.toInt()}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "MixLead ${String.format("%.1f", song.mixLeadSec)}s  ·  " +
                           "MixBreath ${String.format("%.1f", song.mixBreathSec)}s  ·  " +
                           "Entry ${String.format("%.1f", song.mixEntrySec)}s",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}
