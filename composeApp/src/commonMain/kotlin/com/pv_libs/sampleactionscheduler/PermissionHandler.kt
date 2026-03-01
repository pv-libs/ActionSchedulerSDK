package com.pv_libs.sampleactionscheduler

import androidx.compose.runtime.Composable

interface NotificationPermissionHandler {
    fun checkAndRequestPermission(onResult: (Boolean) -> Unit)
}

@Composable
expect fun rememberNotificationPermissionHandler(): NotificationPermissionHandler
