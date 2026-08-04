package com.karaokei.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.karaokei.android.pipeline.PipelineForegroundService
import com.karaokei.core.data.preferences.UserPreferences
import com.karaokei.feature.importer.ImportScreen
import com.karaokei.feature.karaokeplayer.KaraokePlayerScreen
import com.karaokei.feature.library.LibraryScreen
import com.karaokei.feature.library.SongDetailScreen
import com.karaokei.feature.modelmanager.ModelManagerScreen
import com.karaokei.feature.onboarding.OnboardingScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

object Routes {
    const val ONBOARDING = "onboarding"
    const val LIBRARY = "library"
    const val DETAIL = "detail/{songId}"
    const val PLAYER = "player/{songId}"
    const val IMPORT = "import"
    const val MODEL_MANAGER = "model_manager"

    fun detail(songId: String): String = "detail/$songId"
    fun player(songId: String): String = "player/$songId"
}

@HiltViewModel
class RootViewModel @Inject constructor(
    preferences: UserPreferences,
) : ViewModel() {
    val onboardingCompleted: StateFlow<Boolean?> = preferences.onboardingCompleted
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )
}

@Composable
fun KaraokeNavHost(rootViewModel: RootViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val completed by rootViewModel.onboardingCompleted.collectAsState()
    val startDestination = remember(completed) {
        when (completed) {
            null, false -> Routes.ONBOARDING
            true -> Routes.LIBRARY
        }
    }
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(Routes.LIBRARY) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onSongClick = { songId -> navController.navigate(Routes.detail(songId)) },
                onPickFile = { navController.navigate(Routes.IMPORT) },
                onProcess = { songId -> PipelineForegroundService.start(context, songId) },
                onOpenModelManager = { navController.navigate(Routes.MODEL_MANAGER) },
            )
        }
        composable(Routes.IMPORT) {
            ImportScreen(
                onSongImported = { songId ->
                    navController.popBackStack()
                    navController.navigate(Routes.detail(songId))
                },
                onCancel = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("songId") { type = NavType.StringType }),
        ) { entry ->
            val songId = entry.arguments?.getString("songId").orEmpty()
            SongDetailScreen(
                songId = songId,
                onPlay = { navController.navigate(Routes.player(songId)) },
                onProcess = { PipelineForegroundService.start(context, songId) },
            )
        }
        composable(
            route = Routes.PLAYER,
            arguments = listOf(navArgument("songId") { type = NavType.StringType }),
        ) { entry ->
            val songId = entry.arguments?.getString("songId").orEmpty()
            KaraokePlayerScreen(songId = songId)
        }
        composable(Routes.MODEL_MANAGER) {
            ModelManagerScreen(onClose = { navController.popBackStack() })
        }
    }
}
