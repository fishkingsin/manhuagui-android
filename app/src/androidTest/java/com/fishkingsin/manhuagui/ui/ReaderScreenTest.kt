package com.fishkingsin.manhuagui.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import com.fishkingsin.manhuagui.ui.reader.ReaderScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.manhuagui_ffi.ChapterPagesResult

@RunWith(AndroidJUnit4::class)
class ReaderScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun showsErrorWhenChapterHasNoPages() {
        val library = FakeLibrary().apply {
            onGetChapterPages = { _, _ -> ChapterPagesResult(emptyList(), null, null) }
        }
        composeRule.setContent {
            MaterialTheme {
                ReaderScreen(
                    nativeId = 1L,
                    chapterId = 7L,
                    service = library.service(),
                    imageLoader = testImageLoader(composeRule.activity),
                    onBack = {},
                    onOpenChapter = {},
                    localStore = InMemoryLocalStore(),
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("The site format changed. Update the app.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("The site format changed. Update the app.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
    }
}
