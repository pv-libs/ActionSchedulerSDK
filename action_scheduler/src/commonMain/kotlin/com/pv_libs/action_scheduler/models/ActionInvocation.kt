package com.pv_libs.action_scheduler.models


data class ActionInvocation(
    val actionId: String,
    val actionType: String,
    val payloadJson: String,
    val scheduledAtEpochMillis: Long,
)