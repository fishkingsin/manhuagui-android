package com.fishkingsin.manhuagui.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fishkingsin.manhuagui.settings.InMemorySettingsStore
import com.fishkingsin.manhuagui.ui.latest.LatestScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LatestScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun showsBrowseItems() {
        val library = FakeLibrary().apply {
            onBrowseWithin = { _, _, _ ->
                listOf(
                    comicListItem(1L, "Comic A"),
                    comicListItem(2L, "Comic B"),
                )
            }
        }
        composeRule.setContent {
            MaterialTheme {
                LatestScreen(
                    service = library.service(),
                    settingsStore = InMemorySettingsStore(),
                    imageLoader = testImageLoader(composeRule.activity),
                    onSearch = {},
                    onComic = {},
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Comic A").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Comic A").assertIsDisplayed()
        composeRule.onNodeWithText("Comic B").assertIsDisplayed()
    }

    @Test
    fun showsEmptyState() {
        val library = FakeLibrary().apply {
            onBrowseWithin = { _, _, _ -> emptyList() }
        }
        composeRule.setContent {
            MaterialTheme {
                LatestScreen(
                    service = library.service(),
                    settingsStore = InMemorySettingsStore(),
                    imageLoader = testImageLoader(composeRule.activity),
                    onSearch = {},
                    onComic = {},
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("No comics found.").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("No comics found.").assertIsDisplayed()
    }
}
