package com.pv_libs.action_scheduler.internal.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import com.pv_libs.action_scheduler.internal.db.models.ActionExecutionEntity
import com.pv_libs.action_scheduler.internal.db.models.ActionScheduleEntity
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

class InstantConverter {
    @TypeConverter
    fun fromTimestamp(value: Long?): Instant? {
        return value?.let { Instant.fromEpochMilliseconds(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Instant?): Long? {
        return date?.toEpochMilliseconds()
    }
}

@Dao
interface SchedulerDao {
    @Upsert
    suspend fun upsertSchedule(schedule: ActionScheduleEntity)

    @Query("SELECT * FROM ActionSchedule")
    suspend fun getAllSchedules(): List<ActionScheduleEntity>

    @Query("SELECT * FROM ActionSchedule")
    fun getAllSchedulesFlow(): Flow<List<ActionScheduleEntity>>

    @Query("DELETE FROM ActionSchedule WHERE id = :id")
    suspend fun deleteSchedule(id: String)

    @Upsert
    suspend fun upsertExecution(execution: ActionExecutionEntity)

    @Query("SELECT * FROM ActionExecution ORDER BY scheduledAt DESC")
    fun getExecutionsListFlow(): Flow<List<ActionExecutionEntity>>

    @Query("SELECT * FROM ActionExecution WHERE id = :id")
    suspend fun getExecution(id: String): ActionExecutionEntity?

    @Query("SELECT * FROM ActionExecution WHERE scheduleId = :scheduleId")
    suspend fun getExecutionsBySchedule(scheduleId: String): List<ActionExecutionEntity>

    @Query("SELECT * FROM ActionExecution WHERE status = :status")
    suspend fun getExecutionsByStatus(status: String): List<ActionExecutionEntity>

    @Query("DELETE FROM ActionExecution WHERE id NOT IN (SELECT id FROM ActionExecution ORDER BY startedAt DESC LIMIT :keep)")
    suspend fun deleteOverflowExecutions(keep: Long)
}

@Database(entities = [ActionScheduleEntity::class, ActionExecutionEntity::class], version = 3)
@TypeConverters(InstantConverter::class)
abstract class SchedulerRoomDatabase : RoomDatabase() {
    abstract fun schedulerDao(): SchedulerDao
}
