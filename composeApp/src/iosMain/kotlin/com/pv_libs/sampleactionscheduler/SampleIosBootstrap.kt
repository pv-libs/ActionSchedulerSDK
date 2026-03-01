package com.pv_libs.sampleactionscheduler

import com.pv_libs.action_scheduler.ActionNotification
import com.pv_libs.action_scheduler.ActionSchedulerConfig
import com.pv_libs.action_scheduler.ActionSchedulerKit
import com.pv_libs.action_scheduler.NotificationHandler
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

private const val IOS_RUNNER_TASK_ID = "com.pv_libs.action_scheduler.runner"

fun initializeSampleActionSchedulerIos() {
    initializeActionSchedulerIos(taskIds = listOf(IOS_RUNNER_TASK_ID))
}

fun initializeActionSchedulerIos(taskIds: List<String>) {
    val scheduler = ActionSchedulerKit.initialize(
        ActionSchedulerConfig(
            iosTaskIds = taskIds.toSet(),
        )
    )
    val notificationHandler: NotificationHandler = { notification ->
        showNotification(notification)
    }
    registerSampleActionHandlers(scheduler, notificationHandler)
    
    scheduler.setNotificationHandler(notificationHandler)
}

private fun showNotification(notification: ActionNotification) {
    val content = UNMutableNotificationContent().apply {
        setTitle(notification.title)
        setBody(notification.message)
        // Note: userInfo takes Map<Any?, Any>, so we cast the string map
        setUserInfo(notification.payload as Map<Any?, *>)
    }
    val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(1.0, false)
    val request = UNNotificationRequest.requestWithIdentifier(
        identifier = notification.id,
        content = content,
        trigger = trigger
    )
    UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { error ->
        if (error != null) {
            println("Failed to schedule local notification: $error")
        }
    }
}
