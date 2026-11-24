package com.fishkingsin.manhuagui.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fishkingsin.manhuagui.bookmarks.BookmarkUpdateChecker
import com.fishkingsin.manhuagui.data.InMemoryLocalStore
import com.fishkingsin.manhuagui.ui.bookmarks.BookmarksScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarksScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun showsEmptyState() {
        val store = InMemoryLocalStore()
        val library = FakeLibrary()
        val checker = BookmarkUpdateChecker(store, library.service())
        composeRule.setContent {
            MaterialTheme {
                BookmarksScreen(
                    localStore = store,
                    checker = checker,
                    onBack = {},
                    onComic = {},
                    imageLoader = testImageLoader(composeRule.activity),
                )
            }
        }
        composeRule.onNodeWithText("No bookmarks yet.").assertIsDisplayed()
    }
}
