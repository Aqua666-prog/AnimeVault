package com.sergey.animevault.ui.navigation

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sergey.animevault.AnimeVaultApplication
import com.sergey.animevault.ui.library.LibraryRoute
import com.sergey.animevault.ui.library.LibraryViewModel
import com.sergey.animevault.ui.online.OnlineCatalogRoute
import com.sergey.animevault.ui.online.OnlineCatalogViewModel
import com.sergey.animevault.ui.online.OnlineLibraryRoute
import com.sergey.animevault.ui.online.OnlineLibraryViewModel
import com.sergey.animevault.ui.online.OnlineTitleRoute
import com.sergey.animevault.ui.online.OnlineTitleViewModel
import com.sergey.animevault.ui.player.PlayerActivity
import com.sergey.animevault.ui.player.PlayerRoute
import com.sergey.animevault.ui.player.PlayerViewModel
import com.sergey.animevault.ui.settings.SettingsRoute
import com.sergey.animevault.ui.settings.SettingsViewModel
import com.sergey.animevault.ui.title.TitleDetailRoute
import com.sergey.animevault.ui.title.TitleDetailViewModel
import com.sergey.animevault.ui.theme.AnimeBackdrop

private object Routes {
    const val Offline = "offline"
    const val Online = "online"
    const val Settings = "settings"
    const val OnlineLibrary = "online-library"
    const val TitlePattern = "title/{titleId}"
    const val PlayerPattern = "player/{episodeId}"
    const val OnlineTitlePattern = "online-title/{providerId}/{releaseId}"

    fun title(id: Long) = "title/$id"
    fun player(id: Long) = "player/$id"
    fun onlineTitle(providerId: String, id: String) =
        "online-title/${Uri.encode(providerId)}/${Uri.encode(id)}"
}

@Composable
fun AnimeVaultApp(
    isInPictureInPictureMode: Boolean = false,
    onEnterPictureInPicture: () -> Boolean = { false },
) {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as AnimeVaultApplication
    val repository = application.container.libraryRepository
    val onlineRepository = application.container.onlineRepository
    val animeThemeRepository = application.container.animeThemeRepository

    AnimeBackdrop {
        NavHost(
            navController = navController,
            startDestination = Routes.Offline,
            enterTransition = {
                fadeIn(animationSpec = tween(220)) +
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth / 14 },
                        animationSpec = tween(260),
                    )
            },
            exitTransition = { fadeOut(animationSpec = tween(150)) },
            popEnterTransition = {
                fadeIn(animationSpec = tween(190)) +
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> -fullWidth / 18 },
                        animationSpec = tween(235),
                    )
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(150)) +
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth / 18 },
                        animationSpec = tween(215),
                    )
            },
        ) {
        composable(Routes.Offline) {
            val factory = remember(repository) { LibraryViewModel.Factory(repository) }
            val viewModel: LibraryViewModel = viewModel(factory = factory)
            LibraryRoute(
                viewModel = viewModel,
                onOpenTitle = { navController.navigate(Routes.title(it)) },
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onOpenOnline = {
                    navController.navigate(Routes.Online) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.Online) {
            val factory = remember(onlineRepository) {
                OnlineCatalogViewModel.Factory(onlineRepository)
            }
            val viewModel: OnlineCatalogViewModel = viewModel(factory = factory)
            OnlineCatalogRoute(
                viewModel = viewModel,
                onOpenOffline = {
                    if (!navController.popBackStack(Routes.Offline, inclusive = false)) {
                        navController.navigate(Routes.Offline) { launchSingleTop = true }
                    }
                },
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onOpenLibrary = { navController.navigate(Routes.OnlineLibrary) },
                onOpenTitle = { release ->
                    navController.navigate(Routes.onlineTitle(release.providerId, release.id))
                },
                onPlayEpisode = { providerId, releaseId, episodeId ->
                    application.startActivity(
                        PlayerActivity.onlineIntent(
                            context = application,
                            providerId = providerId,
                            releaseId = releaseId,
                            episodeId = episodeId,
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
        }


        composable(Routes.OnlineLibrary) {
            val factory = remember(onlineRepository) {
                OnlineLibraryViewModel.Factory(onlineRepository)
            }
            val viewModel: OnlineLibraryViewModel = viewModel(factory = factory)
            OnlineLibraryRoute(
                viewModel = viewModel,
                onBack = navController::popBackStack,
                onOpenTitle = { providerId, releaseId ->
                    navController.navigate(Routes.onlineTitle(providerId, releaseId))
                },
                onPlayEpisode = { providerId, releaseId, episodeId ->
                    application.startActivity(
                        PlayerActivity.onlineIntent(
                            context = application,
                            providerId = providerId,
                            releaseId = releaseId,
                            episodeId = episodeId,
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
        }

        composable(
            route = Routes.OnlineTitlePattern,
            arguments = listOf(
                navArgument("providerId") { type = NavType.StringType },
                navArgument("releaseId") { type = NavType.StringType },
            ),
            enterTransition = {
                fadeIn(animationSpec = tween(220)) +
                    scaleIn(initialScale = 0.985f, animationSpec = tween(280))
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(160)) +
                    scaleOut(targetScale = 0.988f, animationSpec = tween(190))
            },
        ) { backStackEntry ->
            val providerId = backStackEntry.arguments?.getString("providerId") ?: return@composable
            val releaseId = backStackEntry.arguments?.getString("releaseId") ?: return@composable
            val factory = remember(providerId, releaseId, onlineRepository, animeThemeRepository) {
                OnlineTitleViewModel.Factory(
                    providerId = providerId,
                    releaseId = releaseId,
                    repository = onlineRepository,
                    themeRepository = animeThemeRepository,
                )
            }
            val viewModel: OnlineTitleViewModel = viewModel(factory = factory)
            OnlineTitleRoute(
                viewModel = viewModel,
                onBack = navController::popBackStack,
                onPlayEpisode = { episodeId ->
                    application.startActivity(
                        PlayerActivity.onlineIntent(
                            context = application,
                            providerId = providerId,
                            releaseId = releaseId,
                            episodeId = episodeId,
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
        }

        composable(
            route = Routes.TitlePattern,
            arguments = listOf(navArgument("titleId") { type = NavType.LongType }),
            enterTransition = {
                fadeIn(animationSpec = tween(220)) +
                    scaleIn(initialScale = 0.985f, animationSpec = tween(280))
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(160)) +
                    scaleOut(targetScale = 0.988f, animationSpec = tween(190))
            },
        ) { backStackEntry ->
            val titleId = backStackEntry.arguments?.getLong("titleId") ?: return@composable
            val factory = remember(titleId, repository, onlineRepository) {
                TitleDetailViewModel.Factory(titleId, repository, onlineRepository)
            }
            val viewModel: TitleDetailViewModel = viewModel(factory = factory)
            TitleDetailRoute(
                viewModel = viewModel,
                onBack = navController::popBackStack,
                onPlayEpisode = { navController.navigate(Routes.player(it)) },
                onOpenOfflineTitle = { targetTitleId ->
                    navController.navigate(Routes.title(targetTitleId)) {
                        popUpTo(backStackEntry.destination.id) { inclusive = true }
                    }
                },
                onOpenOnlineTitle = { providerId, releaseId ->
                    navController.navigate(Routes.onlineTitle(providerId, releaseId))
                },
            )
        }

        composable(
            route = Routes.PlayerPattern,
            arguments = listOf(navArgument("episodeId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val episodeId = backStackEntry.arguments?.getLong("episodeId") ?: return@composable
            val factory = remember(episodeId, repository) {
                PlayerViewModel.Factory(episodeId, repository)
            }
            val viewModel: PlayerViewModel = viewModel(factory = factory)
            PlayerRoute(
                viewModel = viewModel,
                onBack = navController::popBackStack,
                isInPictureInPictureMode = isInPictureInPictureMode,
                onEnterPictureInPicture = onEnterPictureInPicture,
                onPlayNext = { nextId ->
                    navController.navigate(Routes.player(nextId)) {
                        popUpTo(backStackEntry.destination.id) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.Settings) {
            val factory = remember(repository, onlineRepository, application.container.offlineScanScheduler) {
                SettingsViewModel.Factory(
                    repository,
                    onlineRepository,
                    application.container.offlineScanScheduler,
                )
            }
            val viewModel: SettingsViewModel = viewModel(factory = factory)
            SettingsRoute(viewModel = viewModel, onBack = navController::popBackStack)
        }
        }
    }
}
