package com.slidedify.app

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.feature.settings.GestureSettingsRoute
import com.slidedify.feature.source.SourceRoute
import com.slidedify.feature.swipe.SwipeRoute
import com.slidedify.feature.transfer.TransferRoute
import kotlinx.serialization.Serializable

@Serializable
object SourceDestination

/** Property names must match [com.slidedify.feature.swipe.SwipeArgs]. */
@Serializable
data class SwipeDestination(val kind: String, val playlistId: String? = null, val title: String)

@Serializable
object SettingsDestination

@Serializable
object TransferDestination

@Composable
fun SlidedifyNavHost() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = SourceDestination) {
        composable<SourceDestination> {
            SourceRoute(
                onSourceSelected = { option ->
                    val playlistId = (option.source as? TrackSource.FromPlaylist)?.id?.value
                    navController.navigate(SwipeDestination(option.source.kind.name, playlistId, option.title))
                },
                onOpenSettings = { navController.navigate(SettingsDestination) },
                onOpenTransfer = { navController.navigate(TransferDestination) },
            )
        }
        composable<TransferDestination> {
            TransferRoute(onBack = { navController.popBackStack() })
        }
        composable<SwipeDestination> {
            SwipeRoute(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(SettingsDestination) },
            )
        }
        composable<SettingsDestination> {
            GestureSettingsRoute(onBack = { navController.popBackStack() })
        }
    }
}
