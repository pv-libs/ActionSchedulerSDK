package com.pv_libs.action_scheduler

fun interface NotificationHandler {
    suspend fun onNotify(notification: ActionNotification)
}

data class ActionNotification(
    val actionId: String,
    val actionType: String,
    val scheduledActionAtEpochMillis: Long,
)
