package com.gijimemo.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gijimemo.ui.home.HomeScreen
import com.gijimemo.ui.import_review.ImportReviewScreen
import com.gijimemo.ui.processing.ProcessingScreen
import com.gijimemo.ui.preview.PreviewScreen
import com.gijimemo.ui.preview.SessionDetailScreen
import com.gijimemo.ui.recording.RecordingScreen
import com.gijimemo.ui.settings.ApiKeyManagementScreen
import com.gijimemo.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val RECORDING = "recording"
    // lang: "ja" | "zh" — 文字起こし時の言語ヒント。省略時はサーバー側の自動判定。
    const val PROCESSING = "processing/{sessionId}?lang={lang}"
    const val IMPORT_REVIEW = "import_review/{sessionId}"
    const val PREVIEW = "preview/{sessionId}"
    const val SESSION = "session/{sessionId}"
    const val SETTINGS = "settings"
    const val API_KEY_MANAGEMENT = "api_key_management"

    fun processing(sessionId: String, lang: String = "") =
        if (lang.isBlank()) "processing/$sessionId" else "processing/$sessionId?lang=$lang"
    fun importReview(sessionId: String) = "import_review/$sessionId"
    fun preview(sessionId: String) = "preview/$sessionId"
    fun session(sessionId: String) = "session/$sessionId"
}

@Composable
fun GijiMemoNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNewRecording = { navController.navigate(Routes.RECORDING) },
                onSessionImported = { id -> navController.navigate(Routes.importReview(id)) },
                onTxtImported = { id -> navController.navigate(Routes.processing(id)) },
                onSessionClick = { id -> navController.navigate(Routes.session(id)) },
                onSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.RECORDING) {
            RecordingScreen(
                onTranscribe = { id, lang -> navController.navigate(Routes.processing(id, lang)) },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(Routes.IMPORT_REVIEW) {
            ImportReviewScreen(
                onTranscribe = { id, lang ->
                    navController.navigate(Routes.processing(id, lang)) {
                        // インポート review は処理開始後に戻る画面ではないので
                        // popUpTo(HOME) でスタックを巻き戻す
                        popUpTo(Routes.HOME)
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.PROCESSING,
            arguments = listOf(
                androidx.navigation.navArgument("sessionId") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("lang") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                    nullable = true
                }
            )
        ) {
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
                onBackToTranscript = { navController.popBackStack(Routes.PROCESSING, false) },
                onBackToMenu = { navController.popBackStack(Routes.HOME, false) },
                onBackToSessionDetail = { sessionId ->
                    navController.navigate(Routes.session(sessionId))
                }
            )
        }
        composable(Routes.SESSION) {
            SessionDetailScreen(
                defaultRecipient = "",
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onApiKeyManagement = { navController.navigate(Routes.API_KEY_MANAGEMENT) }
            )
        }
        composable(Routes.API_KEY_MANAGEMENT) {
            ApiKeyManagementScreen(onBack = { navController.popBackStack() })
        }
    }
}
