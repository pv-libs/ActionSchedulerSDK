package com.pv_libs.action_scheduler.internal.db

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import platform.Foundation.NSHomeDirectory

actual fun getDatabaseBuilder(): RoomDatabase.Builder<SchedulerRoomDatabase> {
    val dbFile = NSHomeDirectory() + "/action-scheduler-db"
    return Room.databaseBuilder<SchedulerRoomDatabase>(
        name = dbFile,
    ).setDriver(BundledSQLiteDriver())
}
