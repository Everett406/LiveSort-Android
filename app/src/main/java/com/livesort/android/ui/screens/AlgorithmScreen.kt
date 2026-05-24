package com.livesort.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
