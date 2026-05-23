package com.livesort.desktop

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.livesort.shared.audio.AudioAnalyzer
import com.livesort.shared.model.Song
import com.livesort.shared.viewmodel.PlaylistViewModel
import java.awt.FileDialog
import java.awt.Frame

private val OrangePrimary = androidx.compose.ui.graphics.Color(0xFFF97316)
private val OrangeDark = androidx.compose.ui.graphics.Color(0xFFEA580C)
private val BackgroundLight = androidx.compose.ui.graphics.Color(0xFFF9F9F8)
private val BackgroundDark = androidx.compose.ui.graphics.Color(0xFF111111)

private val LightColors = lightColorScheme(
    primary = OrangePrimary,
    secondary = OrangeDark,
    background = BackgroundLight,
    surface = androidx.compose.ui.graphics.Color(0xFFF3F1EB)
)

private val DarkColors = darkColorScheme(
    primary = OrangePrimary,
    secondary = OrangeDark,
    background = BackgroundDark,
    surface = androidx.compose.ui.graphics.Color(0xFF1A1A1A)
)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "LiveSort Desktop",
    ) {
        MaterialTheme(colorScheme = LightColors) {
            DesktopApp()
        }
    }
}

@Composable
fun DesktopApp() {
    val viewModel = remember { PlaylistViewModel(AudioAnalyzer()) }

    val songs by viewModel.songs.collectAsState()
    val sortedSongs by viewModel.sortedSongs.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LiveSort Desktop") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    val files = openFilePicker()
                    if (files.isNotEmpty()) {
                        val newSongs = files.mapIndexed { idx, path ->
                            Song(
                                id = System.currentTimeMillis().toInt() + idx,
                                filename = path,
                                title = java.io.File(path).nameWithoutExtension
                            )
                        }
                        viewModel.addSongs(newSongs)
                    }
                },
                modifier = Modifier.fillMaxWidth(0.5f)
            ) {
                Text("选择音频文件")
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isAnalyzing) {
                CircularProgressIndicator()
                Text("正在分析...", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (sortedSongs.isNotEmpty()) {
                Text("排序结果 (${sortedSongs.size} 首)", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(0.8f),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(sortedSongs) { index, song ->
                        SongCard(index = index + 1, song = song)
                    }
                }
            } else if (songs.isEmpty()) {
                Text(
                    text = "选择音频文件开始分析\n支持 MP3, WAV, FLAC, M4A, AAC, OGG",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SongCard(index: Int, song: Song) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "$index. ${song.title.ifEmpty { song.filename }}",
                style = MaterialTheme.typography.bodyLarge
            )
            if (song.isAnalyzed) {
                Text(
                    text = "BPM: ${song.bpm.toInt()}  |  能量: ${(song.energy * 100).toInt()}%  |  情绪: ${song.emotionScore.toInt()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun openFilePicker(): List<String> {
    val dialog = FileDialog(Frame(), "选择音频文件", FileDialog.LOAD).apply {
        isMultipleMode = true
        file = "*.mp3;*.wav;*.flac;*.m4a;*.aac;*.ogg"
        isVisible = true
    }
    return dialog.files?.map { it.absolutePath } ?: emptyList()
}

@Preview
@Composable
fun AppDesktopPreview() {
    MaterialTheme(colorScheme = LightColors) {
        DesktopApp()
    }
}
