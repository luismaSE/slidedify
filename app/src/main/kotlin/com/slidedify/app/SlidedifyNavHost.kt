package com.slidedify.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.slidedify.core.designsystem.AppIcons
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.feature.connections.ConnectionsRoute
import com.slidedify.feature.settings.DestinationsRoute
import com.slidedify.feature.settings.SettingsRoute
import com.slidedify.feature.source.HomeRoute
import com.slidedify.feature.swipe.SwipeRoute
import com.slidedify.feature.transfer.TransferRoute
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable
object HomeDestination

@Serializable
object TransferDestination

@Serializable
object ConnectionsDestination

@Serializable
object SettingsDestination

@Serializable
object DestinationsDestination

/** Property names must match [com.slidedify.feature.swipe.SwipeArgs]. */
@Serializable
data class SwipeDestination(val kind: String, val playlistId: String? = null, val title: String)

/** The four sections of the bottom bar. */
private enum class Tab(val route: Any, val routeClass: KClass<*>, @StringRes val label: Int, val icon: ImageVector) {
    Home(HomeDestination, HomeDestination::class, R.string.tab_home, AppIcons.Home),
    Transfer(TransferDestination, TransferDestination::class, R.string.tab_transfer, AppIcons.Swap),
    Connections(ConnectionsDestination, ConnectionsDestination::class, R.string.tab_connections, AppIcons.Link),
    Settings(SettingsDestination, SettingsDestination::class, R.string.tab_settings, AppIcons.Settings),
}

private fun NavDestination?.isTab(tab: Tab) = this?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true

@Composable
fun SlidedifyNavHost() {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val showBar = Tab.entries.any { destination.isTab(it) }
    // Wide windows (landscape, tablets): a rail on the side instead of the bottom bar.
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    Scaffold(
        // Each screen handles the status bar itself; this only reserves room for the bar.
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(
                visible = showBar && !wide,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = destination.isTab(tab),
                            onClick = { navController.openTab(tab) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
      Row(Modifier.padding(padding)) {
        if (wide && showBar) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.safeDrawingPadding()) {
                Tab.entries.forEach { tab ->
                    NavigationRailItem(
                        selected = destination.isTab(tab),
                        onClick = { navController.openTab(tab) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                        colors = NavigationRailItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        }
        NavHost(
            navController,
            startDestination = HomeDestination,
            modifier = Modifier.weight(1f),
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
        ) {
            composable<HomeDestination> {
                Readable { HomeRoute(
                    onOpenSource = { source, title ->
                        val playlistId = (source as? TrackSource.FromPlaylist)?.id?.value
                        navController.navigate(SwipeDestination(source.kind.name, playlistId, title))
                    },
                    onOpenConnections = { navController.openTab(Tab.Connections) },
                ) }
            }
            composable<TransferDestination> {
                Readable { TransferRoute(onOpenConnections = { navController.openTab(Tab.Connections) }) }
            }
            composable<ConnectionsDestination> {
                Readable { ConnectionsRoute() }
            }
            composable<SettingsDestination> {
                Readable {
                    SettingsRoute(
                        onOpenDestinations = { navController.navigate(DestinationsDestination) },
                        onOpenConnections = { navController.openTab(Tab.Connections) },
                    )
                }
            }
            composable<DestinationsDestination>(
                enterTransition = { slideInHorizontally { it } },
                popExitTransition = { slideOutHorizontally { it } },
            ) {
                Readable { DestinationsRoute(onBack = { navController.popBackStack() }) }
            }
            composable<SwipeDestination>(
                enterTransition = { slideInVertically { it / 6 } + fadeIn() },
                popExitTransition = { slideOutVertically { it / 6 } + fadeOut() },
            ) {
                SwipeRoute(
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(DestinationsDestination) },
                )
            }
        }
      }
    }
}

/** Keeps lists and settings at a readable width on tablets, centred. */
@Composable
private fun Readable(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxHeight().widthIn(max = 720.dp)) { content() }
    }
}

/** Switches section keeping each tab's own back stack and scroll position. */
private fun NavHostController.openTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
