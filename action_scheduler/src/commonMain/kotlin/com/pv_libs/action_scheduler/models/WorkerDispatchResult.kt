package com.pv_libs.action_scheduler.models

internal data class WorkerDispatchResult(
    val success: Boolean,
    val shouldRetry: Boolean = false,
    val message: String? = null,
)
