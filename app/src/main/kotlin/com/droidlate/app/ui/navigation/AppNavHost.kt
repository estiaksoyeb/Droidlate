package com.droidlate.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.droidlate.app.ui.dashboard.DashboardScreen
import com.droidlate.app.ui.dashboard.DashboardViewModel
import com.droidlate.app.ui.editor.EditorScreen
import com.droidlate.app.ui.editor.EditorViewModel
import com.droidlate.app.ui.home.HomeScreen
import com.droidlate.app.ui.home.HomeViewModel

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            val homeViewModel: HomeViewModel = viewModel()
            HomeScreen(
                viewModel = homeViewModel,
                onProjectSelected = { project ->
                    navController.navigate(Screen.Dashboard.createRoute(project.id))
                }
            )
        }

        composable(
            route = Screen.Dashboard.route,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val encodedId = backStackEntry.arguments?.getString("projectId") ?: ""
            val projectId = Screen.decodeParam(encodedId)
            val dashboardViewModel: DashboardViewModel = viewModel()

            DashboardScreen(
                projectId = projectId,
                viewModel = dashboardViewModel,
                onNavigateBack = { navController.popBackStack() },
                onLanguageSelected = { langFolder ->
                    navController.navigate(Screen.Editor.createRoute(projectId, langFolder))
                }
            )
        }

        composable(
            route = Screen.Editor.route,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("langFolder") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val encodedLang = backStackEntry.arguments?.getString("langFolder") ?: ""
            val langFolder = Screen.decodeParam(encodedLang)
            val editorViewModel: EditorViewModel = viewModel()

            EditorScreen(
                langFolder = langFolder,
                viewModel = editorViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
