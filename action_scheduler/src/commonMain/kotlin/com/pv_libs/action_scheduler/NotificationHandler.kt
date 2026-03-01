package com.pv_libs.action_scheduler

fun interface NotificationHandler {
    suspend fun onNotify(notification: ActionNotification)
}

data class ActionNotification(
    val id: String,
    val title: String,
    val message: String,
    val payload: Map<String, String> = emptyMap()
)
