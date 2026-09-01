package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.ui.JarvisViewModel
import com.example.ui.MainScreen
import com.example.ui.SettingsScreen
import com.example.ui.theme.MyApplicationTheme

enum class JarvisScreen {
    MAIN,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    private val viewModel: JarvisViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                JarvisApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun JarvisApp(viewModel: JarvisViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var currentScreen by remember { mutableStateOf(JarvisScreen.MAIN) }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (isGranted) {
            viewModel.toggleAssistant(true)
        }
    }

    val genericPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Trigger recomposition/state check
    }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "ScreenTransition"
    ) { screen ->
        when (screen) {
            JarvisScreen.MAIN -> {
                MainScreen(
                    viewModel = viewModel,
                    onNavigateToSettings = { currentScreen = JarvisScreen.SETTINGS },
                    onRequestMicrophonePermission = {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            genericPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    hasMicrophonePermission = hasMicPermission
                )
            }
            JarvisScreen.SETTINGS -> {
                SettingsScreen(
                    viewModel = viewModel,
                    onNavigateBack = { currentScreen = JarvisScreen.MAIN },
                    onRequestPermission = { permission ->
                        genericPermissionLauncher.launch(permission)
                    }
                )
            }
        }
    }
}
