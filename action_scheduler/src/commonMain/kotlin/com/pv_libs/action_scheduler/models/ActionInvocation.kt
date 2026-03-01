package com.pv_libs.action_scheduler.models


import kotlin.time.Instant

data class ActionInvocation(
    val actionId: String,
    val actionType: String,
    val payloadJson: String,
    val scheduledAt: Instant,
)