package com.musictagrepair.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.musictagrepair.viewmodel.AppViewModel
import com.musictagrepair.ui.screens.FileListScreen
import com.musictagrepair.ui.screens.MatchScreen
import com.musictagrepair.ui.screens.ResultScreen
import com.musictagrepair.ui.screens.ScanScreen

object Routes {
    const val SCAN = "scan"
    const val FILES = "files"
    const val MATCH = "match"
    const val RESULT = "result"
}

@Composable
fun MusicTagNavHost(
    viewModel: AppViewModel = viewModel(),
) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.SCAN) {
        composable(Routes.SCAN) {
            ScanScreen(
                viewModel = viewModel,
                onNavigateToFiles = { nav.navigate(Routes.FILES) },
            )
        }
        composable(Routes.FILES) {
            FileListScreen(
                viewModel = viewModel,
                onNavigateToMatch = { nav.navigate(Routes.MATCH) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.MATCH) {
            MatchScreen(
                viewModel = viewModel,
                onNavigateToResult = { nav.navigate(Routes.RESULT) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.RESULT) {
            ResultScreen(
                viewModel = viewModel,
                onBack = { nav.popBackStack(Routes.FILES, inclusive = false) },
            )
        }
    }
}
