package com.pv_libs.action_scheduler.internal.db.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pv_libs.action_scheduler.models.RunStatus
import kotlin.time.Instant

@Entity(tableName = "ActionExecution")
data class ActionExecutionEntity(
    @PrimaryKey val id: String,
    val scheduleId: String,
    val scheduledAt: Instant,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val status: RunStatus,
    val retryCount: Int,
    val errorCode: String? = null,
    val errorMessage: String? = null
)