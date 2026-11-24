package com.fishkingsin.manhuagui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.fishkingsin.manhuagui.bookmarks.BookmarkUpdateNotification
import com.fishkingsin.manhuagui.ui.AppNav
import com.fishkingsin.manhuagui.ui.theme.ManhuaguiTheme

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContainer = (application as App).container
        val startDestination = if (
            intent?.getStringExtra(BookmarkUpdateNotification.EXTRA_OPEN_ROUTE) ==
            BookmarkUpdateNotification.ROUTE_BOOKMARKS
        ) {
            BookmarkUpdateNotification.ROUTE_BOOKMARKS
        } else {
            "latest"
        }
        setContent {
            ManhuaguiTheme {
                AppNav(
                    libraryService = appContainer.libraryService,
                    imageLoader = appContainer.imageLoader,
                    localStore = appContainer.localStore,
                    checker = appContainer.bookmarkUpdateChecker,
                    startDestination = startDestination,
                )
            }
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
