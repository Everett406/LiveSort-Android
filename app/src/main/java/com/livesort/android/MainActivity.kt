package com.livesort.android



import android.Manifest

import android.content.pm.PackageManager

import android.os.Build

import android.os.Bundle

import androidx.activity.ComponentActivity

import androidx.activity.compose.setContent

import androidx.activity.result.ActivityResultLauncher

import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Surface

import androidx.compose.runtime.Composable

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.ui.Modifier

import androidx.compose.ui.tooling.preview.Preview

import androidx.core.content.ContextCompat

import com.livesort.android.ui.LiveSortApp
import com.livesort.android.ui.theme.LiveSortTheme
import com.livesort.android.util.CrashReporter



class MainActivity : ComponentActivity() {



    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>



    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        CrashReporter.init(this)

        permissionLauncher = registerForActivityResult(

            ActivityResultContracts.RequestMultiplePermissions()

        ) { permissions ->

            val allGranted = permissions.entries.all { it.value }

            if (allGranted) {

                // Permissions granted

            }

        }



        setContent {

            LiveSortTheme {

                Surface(

                    modifier = Modifier.fillMaxSize(),

                    color = MaterialTheme.colorScheme.background

                ) {

                    LiveSortApp()

                }

            }



            LaunchedEffect(Unit) {

                requestAudioPermissions()

            }

        }

    }



    private fun requestAudioPermissions() {

        val permissions = when {

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {

                arrayOf(Manifest.permission.READ_MEDIA_AUDIO)

            }

            else -> {

                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

            }

        }



        val needsRequest = permissions.any {

            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED

        }



        if (needsRequest) {

            permissionLauncher.launch(permissions)

        }

    }

}



@Preview

@Composable

fun AppAndroidPreview() {

    LiveSortTheme {

        LiveSortApp()

    }

}

