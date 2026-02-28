package com.pv_libs.sampleactionscheduler

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.pv_libs.action_scheduler.ActionNotification
import com.pv_libs.action_scheduler.ActionSchedulerConfig
import com.pv_libs.action_scheduler.ActionSchedulerKit

class SampleActionSchedulerApp : Application() {
    init {
        System.setProperty("kotlin-logging-to-android-native", "true")
    }
    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val scheduler = ActionSchedulerKit.initialize(
            ActionSchedulerConfig(
                platformContext = this,
            )
        )

        registerSampleActionHandlers(scheduler)
        scheduler.setNotificationHandler { notification ->
            AndroidSchedulerNotificationHelper.showNotification(
                context = this,
                notification = notification,
            )
        }
    }

    private fun createNotificationChannel() {

        val channel = NotificationChannel(
            AndroidSchedulerNotificationHelper.CHANNEL_ID,
            "Action Scheduler",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Pre-action reminders from Action Scheduler SDK sample"
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
