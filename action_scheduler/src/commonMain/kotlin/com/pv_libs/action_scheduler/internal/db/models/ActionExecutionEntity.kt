package com.pv_libs.action_scheduler.internal.db.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pv_libs.action_scheduler.models.RunStatus

@Entity(tableName = "ActionExecution")
data class ActionExecutionEntity(
    @PrimaryKey val id: String,
    val scheduleId: String,
    val scheduledAtEpochMillis: Long,
    val startedAtEpochMillis: Long?,
    val endedAtEpochMillis: Long?,
    val status: RunStatus,
    val retryCount: Int,
    val errorCode: String? = null,
    val errorMessage: String? = null
)