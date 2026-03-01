package com.pv_libs.sampleactionscheduler

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

class AndroidNotificationPermissionHandler(
    private val hasPermission: () -> Boolean,
    private val requestPermission: ((Boolean) -> Unit) -> Unit
) : NotificationPermissionHandler {
    override fun checkAndRequestPermission(onResult: (Boolean) -> Unit) {
        if (hasPermission()) {
            onResult(true)
        } else {
            requestPermission(onResult)
        }
    }
}

@Composable
actual fun rememberNotificationPermissionHandler(): NotificationPermissionHandler {
    val context = LocalContext.current
    var pendingCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        pendingCallback?.invoke(isGranted)
        pendingCallback = null
    }

    return remember(context, launcher) {
        AndroidNotificationPermissionHandler(
            hasPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
            },
            requestPermission = { callback ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pendingCallback = callback
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    callback(true)
                }
            }
        )
    }
}
