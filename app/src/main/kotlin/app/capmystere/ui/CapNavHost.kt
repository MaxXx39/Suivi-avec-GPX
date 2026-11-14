package app.capmystere.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.capmystere.theme.CapTheme
import app.capmystere.theme.ThemeRepository
import app.capmystere.ui.home.HomeScreen
import app.capmystere.ui.home.SettingsScreen
import app.capmystere.ui.host.EditorScreen
import app.capmystere.ui.host.EditorViewModel
import app.capmystere.ui.host.LiveScreen
import app.capmystere.ui.host.ShareScreen
import app.capmystere.ui.host.StageEditorScreen
import app.capmystere.ui.host.TripListScreen
import app.capmystere.ui.host.TripSettingsScreen
import app.capmystere.ui.traveler.ChallengeScreen
import app.capmystere.ui.traveler.ClueBookScreen
import app.capmystere.ui.traveler.JoinScreen
import app.capmystere.ui.traveler.PermissionsScreen
import app.capmystere.ui.traveler.TravelScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val JOIN = "join?code={code}"
    fun join(code: String? = null) = "join?code=${code.orEmpty()}"
    const val PERMISSIONS = "permissions/{sessionId}"
    fun permissions(sessionId: String) = "permissions/${android.net.Uri.encode(sessionId)}"
    const val TRAVEL = "travel"
    const val CLUES = "clues"
    const val CHALLENGE = "challenge/{challengeId}"
    fun challenge(id: String) = "challenge/${android.net.Uri.encode(id)}"
    const val HOST = "host"
    const val TRIP = "trip/{tripId}"
    fun trip(id: String) = "trip/$id"
    const val EDITOR = "editor"
    const val STAGE = "stage/{stageId}"
    fun stage(id: String) = "stage/$id"
    const val TRIP_SETTINGS = "tripSettings"
    const val SHARE = "share"
    const val LIVE = "live"
}

@Composable
fun CapNavHost(themes: ThemeRepository, deepLinkCode: String?, onDeepLinkConsumed: () -> Unit) {
    val nav = rememberNavController()
    val defaultTheme = remember { themes.get(null) }

    LaunchedEffect(deepLinkCode) {
        if (deepLinkCode != null) {
            nav.navigate(Routes.join(deepLinkCode)) { launchSingleTop = true }
            onDeepLinkConsumed()
        }
    }

    // Thème par défaut de l'application ; chaque voyage applique ensuite le sien.
    CapTheme(defaultTheme) {
        NavHost(
            nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 14 } },
            exitTransition = { fadeOut(tween(200)) },
            popEnterTransition = { fadeIn(tween(260)) },
            popExitTransition = { fadeOut(tween(200)) + slideOutVertically(tween(260)) { it / 14 } },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onTravel = { nav.navigate(Routes.join()) },
                    onResume = { sessionId -> nav.navigate(Routes.permissions(sessionId)) },
                    onHost = { nav.navigate(Routes.HOST) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }

            // ---- Voyageur ----
            composable(Routes.JOIN, arguments = listOf(navArgument("code") { type = NavType.StringType; defaultValue = "" })) { entry ->
                JoinScreen(
                    initialCode = entry.arguments?.getString("code").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onJoined = { sessionId -> nav.navigate(Routes.permissions(sessionId)) { popUpTo(Routes.HOME) } },
                )
            }
            composable(Routes.PERMISSIONS) { entry ->
                PermissionsScreen(
                    sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onReady = { nav.navigate(Routes.TRAVEL) { popUpTo(Routes.HOME) } },
                )
            }
            composable(Routes.TRAVEL) {
                TravelScreen(
                    onExit = { nav.popBackStack() },
                    onClues = { nav.navigate(Routes.CLUES) },
                    onChallenge = { id -> nav.navigate(Routes.challenge(id)) },
                )
            }
            composable(Routes.CLUES) { ClueBookScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.CHALLENGE) { entry ->
                ChallengeScreen(challengeId = entry.arguments?.getString("challengeId").orEmpty(), onBack = { nav.popBackStack() })
            }

            // ---- Hôte ----
            composable(Routes.HOST) {
                TripListScreen(onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate(Routes.trip(id)) })
            }
            navigation(startDestination = Routes.EDITOR, route = Routes.TRIP) {
                @Composable
                fun vm(entry: androidx.navigation.NavBackStackEntry): EditorViewModel {
                    val parent = remember(entry) { nav.getBackStackEntry(Routes.TRIP) }
                    return hiltViewModel(parent)
                }
                composable(Routes.EDITOR) { entry ->
                    EditorScreen(
                        vm(entry),
                        onBack = { nav.popBackStack() },
                        onStage = { id -> nav.navigate(Routes.stage(id)) },
                        onSettings = { nav.navigate(Routes.TRIP_SETTINGS) },
                        onShare = { nav.navigate(Routes.SHARE) },
                        onPreview = { nav.navigate(Routes.TRAVEL) },
                    )
                }
                composable(Routes.STAGE) { entry ->
                    StageEditorScreen(vm(entry), stageId = entry.arguments?.getString("stageId").orEmpty(), onBack = { nav.popBackStack() })
                }
                composable(Routes.TRIP_SETTINGS) { entry -> TripSettingsScreen(vm(entry), onBack = { nav.popBackStack() }) }
                composable(Routes.SHARE) { entry -> ShareScreen(vm(entry), onBack = { nav.popBackStack() }, onLive = { nav.navigate(Routes.LIVE) }) }
                composable(Routes.LIVE) { entry -> LiveScreen(vm(entry), onBack = { nav.popBackStack() }) }
            }
        }
    }
}
