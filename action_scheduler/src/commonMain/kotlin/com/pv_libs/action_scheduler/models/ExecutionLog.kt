package com.pv_libs.action_scheduler.models

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class ExecutionLog(
    val runId: String,
    val actionId: String,
    val triggerId: String,
    val scheduledAt: Instant,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val status: RunStatus,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val platformReason: String? = null,
)
