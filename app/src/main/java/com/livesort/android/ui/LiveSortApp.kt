package com.livesort.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.livesort.android.ui.screens.AlgorithmScreen
import com.livesort.android.ui.screens.FileSelectScreen
import com.livesort.android.ui.screens.FolderPickerScreen
import com.livesort.android.ui.screens.HomeScreen
import com.livesort.android.audio.AudioAnalyzer
import com.livesort.android.viewmodel.PlaylistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Screen {
    data object Home : Screen()
    data object FolderPicker : Screen()
    data class FileSelect(val folder: AudioFolder) : Screen()
    data object Algorithm : Screen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveSortApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    val viewModel = remember { PlaylistViewModel(AudioAnalyzer()) }
    var player by remember { mutableStateOf<CrossfadePlayer?>(null) }

    val sortedSongs by viewModel.sortedSongs.collectAsState()
    val currentPlayingIndex by viewModel.currentPlayingIndex.collectAsState()

    // Lazy initialization: only create ExoPlayer when user actually has songs to play
    LaunchedEffect(sortedSongs, currentPlayingIndex) {
        if (sortedSongs.isNotEmpty() && currentPlayingIndex >= 0) {
            if (player == null) {
                player = CrossfadePlayer(context)
            }
            player?.setPlaylist(sortedSongs, currentPlayingIndex)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }

    Scaffold(
        topBar = {
            when (currentScreen) {
                is Screen.Home -> {
                    TopAppBar(
                        title = { Text("LiveSort") },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                        actions = {
                            IconButton(onClick = { currentScreen = Screen.Algorithm }) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "算法说明"
                                )
                            }
                        }
                    )
                }
                is Screen.Algorithm -> {
                    TopAppBar(
                        title = { Text("算法说明") },
                        navigationIcon = {
                            IconButton(onClick = { currentScreen = Screen.Home }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回"
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                }
                else -> {}
            }
        },
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

                    is Screen.Algorithm -> {
                        AlgorithmScreen()
                    }
                }
            }
        }
    }
}
