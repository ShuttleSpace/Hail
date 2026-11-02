package com.aistra.hail.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aistra.hail.R
import com.aistra.hail.ui.about.AboutScreen
import com.aistra.hail.ui.apps.AppsScreen
import com.aistra.hail.ui.compose.IndexBubble
import com.aistra.hail.ui.compose.LocalIndexBubble
import com.aistra.hail.ui.home.HomeScreen
import com.aistra.hail.ui.settings.SettingsScreen

private const val ROUTE_HOME = "home"
private const val ROUTE_APPS = "apps"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_ABOUT = "about"

private data class BottomItem(val route: String, val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HailRoot(onRemoveOwner: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val items = listOf(
        BottomItem(ROUTE_HOME, R.string.title_home, Icons.Outlined.AcUnit),
        BottomItem(ROUTE_APPS, R.string.title_apps, Icons.Outlined.Android),
        BottomItem(ROUTE_SETTINGS, R.string.title_settings, Icons.Outlined.Settings),
    )
    val showBottomBar = route == ROUTE_HOME || route == ROUTE_APPS || route == ROUTE_SETTINGS
    val indexBubble = remember { mutableStateOf<Char?>(null) }

    CompositionLocalProvider(LocalIndexBubble provides indexBubble) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = ROUTE_HOME,
                    modifier = Modifier.weight(1f),
                ) {
                    composable(ROUTE_HOME) { HomeScreen() }
                    composable(ROUTE_APPS) { AppsScreen() }
                    composable(ROUTE_SETTINGS) {
                        SettingsScreen(
                            onNavigateToAbout = { navController.navigate(ROUTE_ABOUT) },
                            onRemoveOwner = onRemoveOwner,
                        )
                    }
                    composable(ROUTE_ABOUT) {
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = { Text(stringResource(R.string.title_about)) },
                                    navigationIcon = {
                                        IconButton(onClick = { navController.popBackStack() }) {
                                            Icon(Icons.Outlined.ArrowBack, stringResource(R.string.action_back))
                                        }
                                    },
                                )
                            },
                            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                        ) { padding ->
                            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) {
                                AboutScreen()
                            }
                        }
                    }
                }
                if (showBottomBar) {
                    NavigationBar {
                        items.forEach { item ->
                            NavigationBarItem(
                                selected = route == item.route,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(item.icon, stringResource(item.label)) },
                                label = { Text(stringResource(item.label)) },
                            )
                        }
                    }
                }
            }
            IndexBubble(indexBubble, Modifier.align(Alignment.Center))
        }
    }
}
