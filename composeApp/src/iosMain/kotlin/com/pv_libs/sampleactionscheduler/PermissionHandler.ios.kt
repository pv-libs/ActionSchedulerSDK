package com.pv_libs.sampleactionscheduler

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

class IosNotificationPermissionHandler : NotificationPermissionHandler {
    override fun checkAndRequestPermission(onResult: (Boolean) -> Unit) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
        center.requestAuthorizationWithOptions(options) { granted, _ ->
            dispatch_async(dispatch_get_main_queue()) {
                onResult(granted)
            }
        }
    }
}

@Composable
actual fun rememberNotificationPermissionHandler(): NotificationPermissionHandler {
    return remember { IosNotificationPermissionHandler() }
}
