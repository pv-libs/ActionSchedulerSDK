package com.pv_libs.action_scheduler.internal.db.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ActionExecution")
data class ActionExecutionEntity(
    @PrimaryKey val id: String,
    val scheduleId: String,
    val scheduledAtEpochMillis: Long,
    val startedAtEpochMillis: Long?,
    val endedAtEpochMillis: Long?,
    val status: String,
    val retryCount: Int,
    val errorCode: String? = null,
    val errorMessage: String? = null
)