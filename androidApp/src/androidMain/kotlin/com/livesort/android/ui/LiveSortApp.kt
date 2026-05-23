package com.livesort.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.livesort.android.player.CrossfadePlayer
import com.livesort.android.scanner.AudioFolder
import com.livesort.android.scanner.AudioScanner
import com.livesort.android.ui.components.PlayerBar
import com.livesort.android.ui.screens.FileSelectScreen
import com.livesort.android.ui.screens.FolderPickerScreen
import com.livesort.android.ui.screens.HomeScreen
import com.livesort.shared.audio.AudioAnalyzer
import com.livesort.shared.model.Song
import com.livesort.shared.viewmodel.PlaylistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Screen {
    data object Home : Screen()
    data object FolderPicker : Screen()
    data class FileSelect(val folder: AudioFolder) : Screen()
}

@Composable
fun LiveSortApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    val viewModel = remember { PlaylistViewModel(AudioAnalyzer()) }
    val player = remember { CrossfadePlayer(context) }

    // Auto-sync playlist to player when sorted or playing index changes
    val sortedSongs by viewModel.sortedSongs.collectAsState()
    val currentPlayingIndex by viewModel.currentPlayingIndex.collectAsState()

    LaunchedEffect(sortedSongs, currentPlayingIndex) {
        if (sortedSongs.isNotEmpty() && currentPlayingIndex >= 0) {
            player.setPlaylist(sortedSongs, currentPlayingIndex)
        }
    }

    Scaffold(
        bottomBar = {
            if (currentScreen == Screen.Home) {
                PlayerBar(player = player)
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.padding(innerPadding)) {
                when (val screen = currentScreen) {
                    is Screen.Home -> {
                        HomeScreen(
                            viewModel = viewModel,
                            onImportClick = { currentScreen = Screen.FolderPicker }
                        )
                    }

                    is Screen.FolderPicker -> {
                        FolderPickerScreen(
                            onFolderSelected = { folder ->
                                currentScreen = Screen.FileSelect(folder)
                            }
                        )
                    }

                    is Screen.FileSelect -> {
                        FileSelectScreen(
                            folder = screen.folder,
                            onImportSelected = { songs ->
                                currentScreen = Screen.Home
                                scope.launch(Dispatchers.IO) {
                                    val resolved = songs.map { song ->
                                        val uri = android.net.Uri.parse(song.filename)
                                        val localPath = AudioScanner.resolveToLocalPath(context, uri)
                                        if (localPath != null) song.copy(filename = localPath) else song
                                    }
                                    withContext(Dispatchers.Main) {
                                        viewModel.addSongs(resolved)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
