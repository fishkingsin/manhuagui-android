package com.fishkingsin.manhuagui.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil3.ImageLoader
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import com.fishkingsin.manhuagui.data.LocalStore
import com.fishkingsin.manhuagui.ui.detail.DetailScreen
import com.fishkingsin.manhuagui.ui.latest.LatestScreen
import com.fishkingsin.manhuagui.ui.reader.ReaderScreen
import com.fishkingsin.manhuagui.ui.search.SearchScreen

@Composable
fun AppNav(
    libraryService: LibraryService,
    imageLoader: ImageLoader,
    localStore: LocalStore = InMemoryLocalStore(),
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = "latest",
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable("latest") {
            LatestScreen(
                service = libraryService,
                imageLoader = imageLoader,
                onSearch = { navController.navigate("search") },
                onComic = { nativeId -> navController.navigate("comic/$nativeId") },
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
                localStore = localStore,
                onBack = { navController.popBackStack() },
                onChapter = { chapterId ->
                    navController.navigate("reader/$nativeId/$chapterId")
                },
            )
        }
        composable(
            route = "reader/{nativeId}/{chapterId}",
            arguments = listOf(
                navArgument("nativeId") { type = NavType.LongType },
                navArgument("chapterId") { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val arguments = requireNotNull(backStackEntry.arguments)
            val nativeId = arguments.getLong("nativeId")
            val chapterId = arguments.getLong("chapterId")
            ReaderScreen(
                nativeId = nativeId,
                chapterId = chapterId,
                service = libraryService,
                imageLoader = imageLoader,
                localStore = localStore,
                onBack = { navController.popBackStack() },
                onOpenChapter = { nextChapterId ->
                    navController.navigate("reader/$nativeId/$nextChapterId") {
                        popUpTo("reader/{nativeId}/{chapterId}") { inclusive = true }
                    }
                },
            )
        }
    }
}
