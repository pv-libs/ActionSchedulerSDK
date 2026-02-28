package com.pv_libs.action_scheduler.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

@Entity(tableName = "SchedulerState")
data class SchedulerStateEntity(
    @PrimaryKey val id: String,
    val stateJson: String
)

@Entity(tableName = "ExecutionLog")
data class ExecutionLogEntity(
    @PrimaryKey val runId: String,
    val actionId: String,
    val triggerId: String,
    val scheduledAtEpochMillis: Long,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val status: String,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val platformReason: String? = null
)

@Dao
interface SchedulerDao {
    @Query("SELECT stateJson FROM SchedulerState WHERE id = :id")
    suspend fun getState(id: String): String?

    @Upsert
    suspend fun upsertState(state: SchedulerStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ExecutionLogEntity)

    @Query("SELECT * FROM ExecutionLog ORDER BY startedAtEpochMillis DESC LIMIT :limit")
    suspend fun selectRecentAll(limit: Long): List<ExecutionLogEntity>

    @Query("SELECT * FROM ExecutionLog WHERE actionId = :actionId ORDER BY startedAtEpochMillis DESC LIMIT :limit")
    suspend fun selectRecentByAction(actionId: String, limit: Long): List<ExecutionLogEntity>

    @Query("SELECT * FROM ExecutionLog WHERE status IN (:statuses) ORDER BY startedAtEpochMillis DESC LIMIT :limit")
    suspend fun selectRecentByStatuses(statuses: List<String>, limit: Long): List<ExecutionLogEntity>

    @Query("SELECT * FROM ExecutionLog WHERE actionId = :actionId AND status IN (:statuses) ORDER BY startedAtEpochMillis DESC LIMIT :limit")
    suspend fun selectRecentByActionAndStatuses(actionId: String, statuses: List<String>, limit: Long): List<ExecutionLogEntity>

    @Query("DELETE FROM ExecutionLog WHERE runId NOT IN (SELECT runId FROM ExecutionLog ORDER BY startedAtEpochMillis DESC LIMIT :keep)")
    suspend fun deleteOverflow(keep: Long)
}

@Database(entities = [SchedulerStateEntity::class, ExecutionLogEntity::class], version = 1)
abstract class SchedulerRoomDatabase : RoomDatabase() {
    abstract fun schedulerDao(): SchedulerDao
}
