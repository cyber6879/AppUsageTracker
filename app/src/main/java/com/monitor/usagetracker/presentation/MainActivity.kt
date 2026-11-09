package com.monitor.usagetracker.presentation

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.monitor.usagetracker.UsageTrackerApplication
import com.monitor.usagetracker.presentation.ui.screens.AppDetailScreen
import com.monitor.usagetracker.presentation.ui.screens.DashboardScreen
import com.monitor.usagetracker.presentation.ui.screens.PermissionGuideScreen
import com.monitor.usagetracker.presentation.viewmodel.AppDetailViewModel
import com.monitor.usagetracker.presentation.viewmodel.DashboardViewModel
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as UsageTrackerApplication

        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val dashboardViewModel: DashboardViewModel = viewModel(
                        factory = DashboardViewModel.Factory(app.usageRepository)
                    )
                    val dashboardState by dashboardViewModel.uiState.collectAsStateWithLifecycle()
                    val lifecycleOwner = LocalLifecycleOwner.current

                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                dashboardViewModel.checkPermissionAndRefresh()
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }

                    if (!dashboardState.hasPermission) {
                        PermissionGuideScreen(
                            onPermissionVerified = {
                                dashboardViewModel.checkPermissionAndRefresh()
                            }
                        )
                    } else {
                        val navController = rememberNavController()
                        NavHost(
                            navController = navController,
                            startDestination = "dashboard"
                        ) {
                            composable("dashboard") {
                                DashboardScreen(
                                    uiState = dashboardState,
                                    metadataResolver = app.metadataResolver,
                                    onSelectDate = dashboardViewModel::selectDate,
                                    onSearchQueryChange = dashboardViewModel::updateSearchQuery,
                                    onSortOrderChange = dashboardViewModel::updateSortOrder,
                                    onToggleSystemApps = dashboardViewModel::toggleIncludeSystemApps,
                                    onRefreshClick = { dashboardViewModel.triggerSync(forceFull = false) },
                                    onExportData = dashboardViewModel::exportData,
                                    onClearNotifications = dashboardViewModel::clearTransientNotifications,
                                    onAppClick = { pkg, date ->
                                        val encodedPkg = Uri.encode(pkg)
                                        navController.navigate("detail/$encodedPkg/${date.toEpochDay()}")
                                    }
                                )
                            }

                            composable(
                                route = "detail/{packageName}/{epochDay}",
                                arguments = listOf(
                                    navArgument("packageName") { type = NavType.StringType },
                                    navArgument("epochDay") { type = NavType.LongType }
                                )
                            ) { backStackEntry ->
                                val pkg = Uri.decode(
                                    backStackEntry.arguments?.getString("packageName").orEmpty()
                                )
                                val epochDay = backStackEntry.arguments?.getLong("epochDay")
                                    ?: LocalDate.now().toEpochDay()
                                val targetDate = LocalDate.ofEpochDay(epochDay)

                                val detailViewModel: AppDetailViewModel = viewModel(
                                    key = "${pkg}_$epochDay",
                                    factory = AppDetailViewModel.Factory(
                                        packageName = pkg,
                                        initialDate = targetDate,
                                        repository = app.usageRepository
                                    )
                                )
                                val detailState by detailViewModel.uiState.collectAsStateWithLifecycle()

                                AppDetailScreen(
                                    uiState = detailState,
                                    metadataResolver = app.metadataResolver,
                                    onBackClick = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
