package com.pv_libs.sampleactionscheduler

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pv_libs.action_scheduler.ActionNotification

object AndroidSchedulerNotificationHelper {
    const val CHANNEL_ID = "action_scheduler_channel"

    fun showNotification(
        context: Context,
        notification: ActionNotification,
    ) {
        val title = "Upcoming scheduled action"
        val body = "${notification.actionType} will run soon (actionId=${notification.actionId})"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            val fallback = context.getSystemService(NotificationManager::class.java)
            fallback.cancel(notification.actionId.hashCode())
            return
        }

        manager.notify(notification.actionId.hashCode(), builder.build())
    }
}
