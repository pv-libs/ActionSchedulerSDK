package com.pv_libs.action_scheduler.internal.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

lateinit var applicationContext: Context

actual fun getDatabaseBuilder(): RoomDatabase.Builder<SchedulerRoomDatabase> {
    return Room.databaseBuilder(
        applicationContext,
        SchedulerRoomDatabase::class.java,
        "action-scheduler-db"
    )
}
