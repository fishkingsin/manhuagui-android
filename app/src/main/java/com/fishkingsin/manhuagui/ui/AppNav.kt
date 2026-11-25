package com.fishkingsin.manhuagui.ui

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil3.ImageLoader
import com.fishkingsin.manhuagui.bookmarks.BookmarkUpdateChecker
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import com.fishkingsin.manhuagui.data.LocalStore
import com.fishkingsin.manhuagui.downloads.ArchiveExporter
import com.fishkingsin.manhuagui.downloads.OfflineDownloadManager
import com.fishkingsin.manhuagui.settings.SettingsStore
import com.fishkingsin.manhuagui.ui.bookmarks.BookmarksScreen
import com.fishkingsin.manhuagui.ui.detail.DetailScreen
import com.fishkingsin.manhuagui.ui.latest.LatestScreen
import com.fishkingsin.manhuagui.ui.reader.ReaderScreen
import com.fishkingsin.manhuagui.ui.search.SearchScreen
import com.fishkingsin.manhuagui.ui.settings.SettingsScreen
import com.fishkingsin.manhuagui.ui.settings.SettingsViewModel

@Composable
fun AppNav(
    libraryService: LibraryService,
    imageLoader: ImageLoader,
    settingsStore: SettingsStore,
    downloadManager: OfflineDownloadManager,
    archiveExporter: ArchiveExporter,
    downloadRoot: String,
    onIntervalChanged: (Int) -> Unit = {},
    localStore: LocalStore = InMemoryLocalStore(),
    checker: BookmarkUpdateChecker = BookmarkUpdateChecker(localStore, libraryService),
    startDestination: String = "latest",
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val scope = rememberCoroutineScope()
    val settingsViewModel = remember(settingsStore, onIntervalChanged, scope) {
        SettingsViewModel(settingsStore, onIntervalChanged, scope)
    }
    val selectTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (currentRoute in TOP_LEVEL_ROUTES) {
            TopLevelTabBar(selectedRoute = currentRoute, onSelect = selectTab)
        }
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.weight(1f),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable("latest") {
                LatestScreen(
                    service = libraryService,
                    settingsStore = settingsStore,
                    imageLoader = imageLoader,
                    onSearch = { navController.navigate("search") },
                    onComic = { nativeId -> navController.navigate("comic/$nativeId") },
                )
            }
            composable("bookmarks") {
                BookmarksScreen(
                    localStore = localStore,
                    checker = checker,
                    onComic = { nativeId -> navController.navigate("comic/$nativeId") },
                    imageLoader = imageLoader,
                )
            }
            composable("settings") {
                SettingsScreen(
                    onBack = { selectTab("latest") },
                    viewModel = settingsViewModel,
                )
            }
            composable("search") {
                SearchScreen(
                    service = libraryService,
                    imageLoader = imageLoader,
                    onBack = { navController.popBackStack() },
                    onComic = { nativeId -> navController.navigate("comic/$nativeId") },
                )
            }
            composable(
                route = "comic/{nativeId}",
                arguments = listOf(navArgument("nativeId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val nativeId = requireNotNull(backStackEntry.arguments?.getLong("nativeId"))
                DetailScreen(
                    nativeId = nativeId,
                    service = libraryService,
                    imageLoader = imageLoader,
                    offlineDownloadManager = downloadManager,
                    archiveExporter = archiveExporter,
                    localStore = localStore,
                    onBack = { navController.popBackStack() },
                    onChapter = { chapterId, chapterTitle ->
                        navController.navigate(
                            "reader/$nativeId/$chapterId?chapterTitle=${Uri.encode(chapterTitle)}",
                        )
                    },
                )
            }
            composable(
                route = "reader/{nativeId}/{chapterId}?chapterTitle={chapterTitle}",
                arguments = listOf(
                    navArgument("nativeId") { type = NavType.LongType },
                    navArgument("chapterId") { type = NavType.LongType },
                    navArgument("chapterTitle") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { backStackEntry ->
                val arguments = requireNotNull(backStackEntry.arguments)
                val nativeId = arguments.getLong("nativeId")
                val chapterId = arguments.getLong("chapterId")
                ReaderScreen(
                    nativeId = nativeId,
                    chapterId = chapterId,
                    chapterTitle = arguments.getString("chapterTitle").orEmpty(),
                    service = libraryService,
                    imageLoader = imageLoader,
                    localStore = localStore,
                    settingsStore = settingsStore,
                    downloadRoot = downloadRoot,
                    onBack = { navController.popBackStack() },
                    onOpenChapter = { nextChapterId ->
                        navController.navigate("reader/$nativeId/$nextChapterId") {
                            popUpTo("reader/{nativeId}/{chapterId}?chapterTitle={chapterTitle}") { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TopLevelTabBar(
    selectedRoute: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TabButton("Browse", "latest", selectedRoute, onSelect, Modifier.weight(1f))
        TabButton("Bookmarks", "bookmarks", selectedRoute, onSelect, Modifier.weight(1f))
        TabButton("Settings", "settings", selectedRoute, onSelect, Modifier.weight(1f))
    }
}

@Composable
private fun TabButton(
    label: String,
    route: String,
    selectedRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    val selected = route == selectedRoute
    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(if (selected) contentColor else backgroundColor)
            .border(1.dp, contentColor, RectangleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onSelect(route) },
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) backgroundColor else contentColor,
            fontSize = 16.sp,
        )
    }
}

private val TOP_LEVEL_ROUTES = setOf("latest", "bookmarks", "settings")
