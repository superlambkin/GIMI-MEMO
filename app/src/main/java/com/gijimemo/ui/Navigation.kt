package com.gijimemo.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gijimemo.ui.home.HomeScreen
import com.gijimemo.ui.processing.ProcessingScreen
import com.gijimemo.ui.preview.PreviewScreen
import com.gijimemo.ui.preview.SessionDetailScreen
import com.gijimemo.ui.recording.RecordingScreen
import com.gijimemo.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val RECORDING = "recording"
    const val PROCESSING = "processing/{sessionId}"
    const val PREVIEW = "preview/{sessionId}"
    const val SESSION = "session/{sessionId}"
    const val SETTINGS = "settings"

    fun processing(sessionId: String) = "processing/$sessionId"
    fun preview(sessionId: String) = "preview/$sessionId"
    fun session(sessionId: String) = "session/$sessionId"
}

@Composable
fun GijiMemoNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNewRecording = { navController.navigate(Routes.RECORDING) },
                onSessionClick = { id -> navController.navigate(Routes.session(id)) },
                onSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.RECORDING) {
            RecordingScreen(
                onTranscribe = { id -> navController.navigate(Routes.processing(id)) },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(Routes.PROCESSING) {
            ProcessingScreen(
                onComplete = { id -> navController.navigate(Routes.preview(id)) {
                    popUpTo(Routes.HOME)
                } },
                onError = { navController.popBackStack() }
            )
        }
        composable(Routes.PREVIEW) {
            PreviewScreen(
                defaultRecipient = "",
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SESSION) {
            SessionDetailScreen(
                defaultRecipient = "",
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
