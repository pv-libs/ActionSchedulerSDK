package com.pv_libs.action_scheduler.internal.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.pv_libs.action_scheduler.internal.db.models.ActionExecutionEntity
import com.pv_libs.action_scheduler.internal.db.models.ActionScheduleEntity


@Dao
interface SchedulerDao {
    @Upsert
    suspend fun upsertSchedule(schedule: ActionScheduleEntity)

    @Query("SELECT * FROM ActionSchedule WHERE id = :id")
    suspend fun getSchedule(id: String): ActionScheduleEntity?

    @Query("SELECT * FROM ActionSchedule")
    suspend fun getAllSchedules(): List<ActionScheduleEntity>

    @Query("DELETE FROM ActionSchedule WHERE id = :id")
    suspend fun deleteSchedule(id: String)

    @Upsert
    suspend fun upsertExecution(execution: ActionExecutionEntity)

    @Query("SELECT * FROM ActionExecution WHERE id = :id")
    suspend fun getExecution(id: String): ActionExecutionEntity?

    @Query("SELECT * FROM ActionExecution WHERE scheduleId = :scheduleId")
    suspend fun getExecutionsBySchedule(scheduleId: String): List<ActionExecutionEntity>

    @Query("SELECT * FROM ActionExecution WHERE status = :status")
    suspend fun getExecutionsByStatus(status: String): List<ActionExecutionEntity>

    @Query("SELECT * FROM ActionExecution ORDER BY startedAtEpochMillis DESC LIMIT :limit")
    suspend fun selectRecentExecutions(limit: Long): List<ActionExecutionEntity>

    @Query("SELECT * FROM ActionExecution WHERE scheduleId = :scheduleId ORDER BY startedAtEpochMillis DESC LIMIT :limit")
    suspend fun selectRecentExecutionsBySchedule(scheduleId: String, limit: Long): List<ActionExecutionEntity>

    @Query("SELECT * FROM ActionExecution WHERE status IN (:statuses) ORDER BY startedAtEpochMillis DESC LIMIT :limit")
    suspend fun selectRecentExecutionsByStatuses(statuses: List<String>, limit: Long): List<ActionExecutionEntity>

    @Query("SELECT * FROM ActionExecution WHERE scheduleId = :scheduleId AND status IN (:statuses) ORDER BY startedAtEpochMillis DESC LIMIT :limit")
    suspend fun selectRecentExecutionsByScheduleAndStatuses(scheduleId: String, statuses: List<String>, limit: Long): List<ActionExecutionEntity>

    @Query("DELETE FROM ActionExecution WHERE id NOT IN (SELECT id FROM ActionExecution ORDER BY startedAtEpochMillis DESC LIMIT :keep)")
    suspend fun deleteOverflowExecutions(keep: Long)
}

@Database(entities = [ActionScheduleEntity::class, ActionExecutionEntity::class], version = 2)
abstract class SchedulerRoomDatabase : RoomDatabase() {
    abstract fun schedulerDao(): SchedulerDao
}
