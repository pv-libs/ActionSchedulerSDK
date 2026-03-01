package com.pv_libs.action_scheduler.models

enum class RunStatus {
    SUCCESS,
    FAILED,
    DEDUPE_SKIPPED,
    NOTIFICATION_SENT,
    HANDLER_NOT_FOUND,
    PENDING
}