package com.fishkingsin.manhuagui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.fishkingsin.manhuagui.ui.AppNav
import com.fishkingsin.manhuagui.ui.theme.ManhuaguiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContainer = (application as App).container
        setContent {
            ManhuaguiTheme {
                AppNav(
                    libraryService = appContainer.libraryService,
                    imageLoader = appContainer.imageLoader,
                    localStore = appContainer.localStore,
                )
            }
        }
    }
}
