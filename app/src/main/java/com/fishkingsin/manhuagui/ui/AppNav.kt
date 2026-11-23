package com.fishkingsin.manhuagui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil3.ImageLoader
import com.fishkingsin.manhuagui.core.LibraryService
import com.fishkingsin.manhuagui.ui.latest.LatestScreen

@Composable
fun AppNav(
    libraryService: LibraryService,
    imageLoader: ImageLoader,
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "latest") {
        composable("latest") {
            LatestScreen(
                service = libraryService,
                imageLoader = imageLoader,
                onSearch = { navController.navigate("search") },
                onComic = { nativeId -> navController.navigate("comic/$nativeId") },
            )
        }
        composable("search") {
            PlaceholderDestination("Search")
        }
        composable(
            route = "comic/{nativeId}",
            arguments = listOf(navArgument("nativeId") { type = NavType.LongType }),
        ) {
            PlaceholderDestination("Comic")
        }
        composable(
            route = "reader/{nativeId}/{chapterId}",
            arguments = listOf(
                navArgument("nativeId") { type = NavType.LongType },
                navArgument("chapterId") { type = NavType.LongType },
            ),
        ) {
            PlaceholderDestination("Reader")
        }
    }
}

@Composable
private fun PlaceholderDestination(label: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = Color.Black)
    }
}
