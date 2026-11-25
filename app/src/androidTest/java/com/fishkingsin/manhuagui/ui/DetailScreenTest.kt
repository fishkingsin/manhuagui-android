package com.fishkingsin.manhuagui.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import com.fishkingsin.manhuagui.downloads.ArchiveExporter
import com.fishkingsin.manhuagui.downloads.OfflineDownloadManager
import com.fishkingsin.manhuagui.downloads.ServiceController
import com.fishkingsin.manhuagui.ui.detail.DetailScreen
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun showsTitleAndChapters() {
        val library = FakeLibrary().apply {
            onGetComic = { id ->
                comicDetails(
                    id = id,
                    title = "One Piece",
                    chapters = listOf(
                        chapterItem(1L, "Chapter 1"),
                        chapterItem(2L, "Chapter 2"),
                    ),
                )
            }
        }
        val localStore = InMemoryLocalStore()
        val service = library.service()
        val downloadRoot = File(composeRule.activity.cacheDir, "downloads").absolutePath
        val downloadManager = OfflineDownloadManager(
            service = service,
            localStore = localStore,
            downloadRoot = downloadRoot,
            serviceController = ServiceController { },
        )
        val archiveExporter = ArchiveExporter(
            service = service,
            downloadRoot = downloadRoot,
            exportRoot = File(composeRule.activity.cacheDir, "exports").absolutePath,
        )
        composeRule.setContent {
            MaterialTheme {
                DetailScreen(
                    nativeId = 1L,
                    service = service,
                    imageLoader = testImageLoader(composeRule.activity),
                    offlineDownloadManager = downloadManager,
                    archiveExporter = archiveExporter,
                    localStore = localStore,
                    onBack = {},
                    onChapter = { _, _ -> },
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("One Piece").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("One Piece").assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Chapter 1"))
        composeRule.onNodeWithText("Chapter 1").assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Chapter 2"))
        composeRule.onNodeWithText("Chapter 2").assertIsDisplayed()
    }
}
