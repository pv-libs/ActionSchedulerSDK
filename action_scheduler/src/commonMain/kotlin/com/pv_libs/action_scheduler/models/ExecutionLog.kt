package com.pv_libs.action_scheduler.models

import kotlinx.serialization.Serializable


@Serializable
data class ExecutionLog(
    val runId: String,
    val actionId: String,
    val triggerId: String,
    val scheduledAtEpochMillis: Long,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val status: RunStatus,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val platformReason: String? = null,
)
