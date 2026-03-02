package com.pv_libs.sampleactionscheduler

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pv_libs.action_scheduler.ActionNotification
import com.pv_libs.action_scheduler.ActionSchedulerConfig
import com.pv_libs.action_scheduler.ActionSchedulerKit
import com.pv_libs.action_scheduler.NotificationHandler
import com.pv_libs.action_scheduler.logger

class SampleActionSchedulerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        val notificationHandler: NotificationHandler = { notification ->
            showNotification(notification)
        }

        try {
            val scheduler = ActionSchedulerKit.initialize(
                SampleActionHandlerFactory(notificationHandler),
                ActionSchedulerConfig(
                    platformContext = this,
                )
            )
            scheduler.setNotificationHandler(notificationHandler)
        } catch (ex: Exception){
            logger(ex.toString())
        }

    }

    fun showNotification(notification: ActionNotification) {
        val context = this
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val channelId = "action_scheduler_channel"

        // Create channel if needed
        val channel = NotificationChannel(
            channelId,
            "Action Scheduler",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(channel)

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notification.title)
            .setContentText(notification.message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)


        manager.notify(notification.id.hashCode(), builder.build())
    }


    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "action_scheduler_channel",
            "Action Scheduler",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Pre-action reminders from Action Scheduler SDK sample"
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
