package com.sergey.animevault.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sergey.animevault.AnimeVaultApplication
import com.sergey.animevault.ui.design.VaultMotion
import com.sergey.animevault.ui.history.HistoryRoute
import com.sergey.animevault.ui.history.HistoryViewModel
import com.sergey.animevault.ui.home.HomeRoute
import com.sergey.animevault.ui.home.HomeViewModel
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
import com.sergey.animevault.ui.statistics.StatisticsRoute
import com.sergey.animevault.ui.statistics.StatisticsViewModel
import com.sergey.animevault.ui.title.TitleDetailRoute
import com.sergey.animevault.ui.title.TitleDetailViewModel
import com.sergey.animevault.ui.theme.AnimeBackdrop
import com.sergey.animevault.ui.theme.vaultMotionDuration

private object Routes {
    const val Home = "home"
    const val Offline = "offline"
    const val Online = "online"
    const val History = "history"
    const val Settings = "settings"
    const val Statistics = "statistics"
    const val OnlineLibrary = "online-library"
    const val LocalTitlePattern = "media-title/local/{titleId}"
    const val PlayerPattern = "player/{episodeId}"
    const val OnlineTitlePattern = "media-title/online/{providerId}/{releaseId}"

    fun title(id: Long) = "media-title/local/$id"
    fun player(id: Long) = "player/$id"
    fun onlineTitle(providerId: String, id: String) =
        "media-title/online/${Uri.encode(providerId)}/${Uri.encode(id)}"
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun VaultSharedDestination(
    animatedVisibilityScope: AnimatedVisibilityScope,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalVaultAnimatedVisibilityScope provides animatedVisibilityScope,
        content = content,
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AnimeVaultApp(
    isInPictureInPictureMode: Boolean = false,
    onEnterPictureInPicture: () -> Boolean = { false },
) {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as AnimeVaultApplication
    val repository = application.container.libraryRepository
    val onlineRepository = application.container.onlineRepository
    val uiPreferences = application.container.uiPreferences
    val animeThemeRepository = application.container.animeThemeRepository
    val aniListMetadataRepository = application.container.aniListMetadataRepository
    val aniListFranchiseRepository = application.container.aniListFranchiseRepository
    val aniListSyncRepository = application.container.aniListSyncRepository
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val rootItems = remember {
        listOf(
            VaultRootNavItem(Routes.Home, "Главная", Icons.Outlined.Home),
            VaultRootNavItem(Routes.Offline, "Медиатека", Icons.Outlined.Folder),
            VaultRootNavItem(Routes.Online, "Онлайн", Icons.Outlined.Cloud),
            VaultRootNavItem(Routes.History, "История", Icons.Outlined.History),
        )
    }
    val rootRoutes = remember(rootItems) { rootItems.map(VaultRootNavItem::route).toSet() }
    val navigateRoot: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(Routes.Home) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val motionFast = vaultMotionDuration(VaultMotion.fast)
    val motionStandard = vaultMotionDuration(VaultMotion.standard)
    val motionReveal = vaultMotionDuration(VaultMotion.reveal)

    AnimeBackdrop {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 720.dp
            val showRootNavigation = currentRoute != null && currentRoute in rootRoutes
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    if (showRootNavigation && !useRail) {
                        VaultBottomNavigation(
                            items = rootItems,
                            currentRoute = currentRoute,
                            onNavigate = navigateRoot,
                        )
                    }
                },
            ) { shellPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(shellPadding),
                ) {
                    if (showRootNavigation && useRail) {
                        VaultNavigationRail(
                            items = rootItems,
                            currentRoute = currentRoute,
                            onNavigate = navigateRoot,
                        )
                    }
                    SharedTransitionLayout(modifier = Modifier.weight(1f)) {
                        CompositionLocalProvider(LocalVaultSharedTransitionScope provides this) {
                            NavHost(
                        navController = navController,
                        startDestination = Routes.Home,
                        modifier = Modifier.fillMaxSize(),
                        enterTransition = {
                            fadeIn(animationSpec = tween(motionStandard)) +
                                slideInHorizontally(
                                    initialOffsetX = { fullWidth -> fullWidth / 14 },
                                    animationSpec = tween(motionReveal),
                                )
                        },
                        exitTransition = { fadeOut(animationSpec = tween(motionFast)) },
                        popEnterTransition = {
                            fadeIn(animationSpec = tween(motionStandard)) +
                                slideInHorizontally(
                                    initialOffsetX = { fullWidth -> -fullWidth / 18 },
                                    animationSpec = tween(motionStandard),
                                )
                        },
                        popExitTransition = {
                            fadeOut(animationSpec = tween(motionFast)) +
                                slideOutHorizontally(
                                    targetOffsetX = { fullWidth -> fullWidth / 18 },
                                    animationSpec = tween(motionStandard),
                                )
                        },
                    ) {
                        composable(Routes.Home) {
                            val factory = remember(repository, onlineRepository) {
                                HomeViewModel.Factory(repository, onlineRepository)
                            }
                            val viewModel: HomeViewModel = viewModel(factory = factory)
                            HomeRoute(
                                viewModel = viewModel,
                                onOpenOffline = { navigateRoot(Routes.Offline) },
                                onOpenOnline = { navigateRoot(Routes.Online) },
                                onOpenSettings = { navController.navigate(Routes.Settings) },
                                onOpenStatistics = { navController.navigate(Routes.Statistics) },
                                onOpenLocalTitle = { navController.navigate(Routes.title(it)) },
                                onPlayLocalEpisode = { navController.navigate(Routes.player(it)) },
                                onOpenOnlineTitle = { providerId, releaseId ->
                                    navController.navigate(Routes.onlineTitle(providerId, releaseId))
                                },
                                onPlayOnlineEpisode = { providerId, releaseId, episodeId ->
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

                        composable(Routes.Offline) {
                            VaultSharedDestination(this) {
                                val factory = remember(repository, uiPreferences) { LibraryViewModel.Factory(repository, uiPreferences) }
                                val viewModel: LibraryViewModel = viewModel(factory = factory)
                                LibraryRoute(
                                    viewModel = viewModel,
                                    onOpenTitle = { navController.navigate(Routes.title(it)) },
                                    onOpenSettings = { navController.navigate(Routes.Settings) },
                                )
                            }
                        }

                        composable(Routes.Online) {
                            VaultSharedDestination(this) {
                                val factory = remember(onlineRepository, uiPreferences) {
                                    OnlineCatalogViewModel.Factory(onlineRepository, uiPreferences)
                                }
                                val viewModel: OnlineCatalogViewModel = viewModel(factory = factory)
                                OnlineCatalogRoute(
                                    viewModel = viewModel,
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
                        }


                        composable(Routes.History) {
                            val factory = remember(repository, onlineRepository) {
                                HistoryViewModel.Factory(repository, onlineRepository)
                            }
                            val viewModel: HistoryViewModel = viewModel(factory = factory)
                            HistoryRoute(
                                viewModel = viewModel,
                                onOpenSettings = { navController.navigate(Routes.Settings) },
                                onOpenLocalTitle = { navController.navigate(Routes.title(it)) },
                                onPlayLocalEpisode = { navController.navigate(Routes.player(it)) },
                                onOpenOnlineTitle = { providerId, releaseId ->
                                    navController.navigate(Routes.onlineTitle(providerId, releaseId))
                                },
                                onPlayOnlineEpisode = { providerId, releaseId, episodeId ->
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
                                fadeIn(animationSpec = tween(motionStandard)) +
                                    scaleIn(initialScale = 0.985f, animationSpec = tween(motionReveal))
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = tween(motionFast)) +
                                    scaleOut(targetScale = 0.988f, animationSpec = tween(motionStandard))
                            },
                        ) { backStackEntry ->
                            val providerId = backStackEntry.arguments?.getString("providerId") ?: return@composable
                            val releaseId = backStackEntry.arguments?.getString("releaseId") ?: return@composable
                            VaultSharedDestination(this) {
                            val factory = remember(providerId, releaseId, onlineRepository, animeThemeRepository, repository) {
                                OnlineTitleViewModel.Factory(
                                    providerId = providerId,
                                    releaseId = releaseId,
                                    repository = onlineRepository,
                                    themeRepository = animeThemeRepository,
                                    libraryRepository = repository,
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
                                onOpenLocalTitle = { localTitleId ->
                                    navController.navigate(Routes.title(localTitleId))
                                },
                            )
                            }
                        }

                        composable(
                            route = Routes.LocalTitlePattern,
                            arguments = listOf(navArgument("titleId") { type = NavType.LongType }),
                            enterTransition = {
                                fadeIn(animationSpec = tween(motionStandard)) +
                                    scaleIn(initialScale = 0.985f, animationSpec = tween(motionReveal))
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = tween(motionFast)) +
                                    scaleOut(targetScale = 0.988f, animationSpec = tween(motionStandard))
                            },
                        ) { backStackEntry ->
                            val titleId = backStackEntry.arguments?.getLong("titleId") ?: return@composable
                            VaultSharedDestination(this) {
                            val factory = remember(titleId, repository, onlineRepository, aniListMetadataRepository, aniListFranchiseRepository) {
                                TitleDetailViewModel.Factory(
                                    titleId = titleId,
                                    repository = repository,
                                    onlineRepository = onlineRepository,
                                    metadataRepository = aniListMetadataRepository,
                                    franchiseRepository = aniListFranchiseRepository,
                                )
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
                        }

                        composable(
                            route = Routes.PlayerPattern,
                            arguments = listOf(navArgument("episodeId") { type = NavType.LongType }),
                        ) { backStackEntry ->
                            val episodeId = backStackEntry.arguments?.getLong("episodeId") ?: return@composable
                            val factory = remember(episodeId, repository, aniListSyncRepository) {
                                PlayerViewModel.Factory(episodeId, repository, aniListSyncRepository)
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

                        composable(Routes.Statistics) {
                            val factory = remember(repository, onlineRepository) {
                                StatisticsViewModel.Factory(repository, onlineRepository)
                            }
                            val viewModel: StatisticsViewModel = viewModel(factory = factory)
                            StatisticsRoute(viewModel = viewModel, onBack = navController::popBackStack)
                        }

                        composable(Routes.Settings) {
                            val factory = remember(repository, onlineRepository, application.container.offlineScanScheduler, uiPreferences) {
                                SettingsViewModel.Factory(
                                    repository,
                                    onlineRepository,
                                    application.container.offlineScanScheduler,
                                    aniListSyncRepository,
                                    application.container.backupRepository,
                                    uiPreferences,
                                )
                            }
                            val viewModel: SettingsViewModel = viewModel(factory = factory)
                            SettingsRoute(viewModel = viewModel, onBack = navController::popBackStack)
                        }
                            }
                        }
                    }
                }
            }
        }
    }
}
