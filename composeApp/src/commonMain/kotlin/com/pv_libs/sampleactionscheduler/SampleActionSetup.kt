package com.pv_libs.sampleactionscheduler

import com.pv_libs.action_scheduler.ActionConstraints
import com.pv_libs.action_scheduler.ActionHandlerResult
import com.pv_libs.action_scheduler.ActionScheduler
import com.pv_libs.action_scheduler.ActionSpec
import com.pv_libs.action_scheduler.RecurrenceRule
import com.pv_libs.action_scheduler.RegistrationResult
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

const val DAILY_ACTION_ID = "daily_balance_nudge"
const val MONTHLY_ACTION_ID = "monthly_auto_pay_reminder"

private const val ACTION_TYPE_BALANCE_NUDGE = "BALANCE_NUDGE"
private const val ACTION_TYPE_MONTHLY_AUTOPAY = "MONTHLY_AUTOPAY_REMINDER"

fun registerSampleActionHandlers(scheduler: ActionScheduler) {
    scheduler.registerHandler(ACTION_TYPE_BALANCE_NUDGE) { invocation ->
        ActionHandlerResult.Success
    }

    scheduler.registerHandler(ACTION_TYPE_MONTHLY_AUTOPAY) { invocation ->
        val shouldFail = invocation.scheduledAtEpochMillis % 5L == 0L
        if (shouldFail) {
            ActionHandlerResult.Failure(
                message = "Simulated transient failure for monthly reminder",
                retryable = true,
                errorCode = "SIMULATED_RETRYABLE",
            )
        } else {
            ActionHandlerResult.Success
        }
    }
}

suspend fun scheduleDailySampleAction(scheduler: ActionScheduler): RegistrationResult {
    val runAtLocal = futureLocalDateTime(minutesFromNow = 2)
    val spec = ActionSpec(
        actionId = DAILY_ACTION_ID,
        actionType = ACTION_TYPE_BALANCE_NUDGE,
        payloadJson = "{\"title\":\"Daily balance nudge\"}",
        recurrence = RecurrenceRule.Daily(
            hour = runAtLocal.hour,
            minute = runAtLocal.minute,
        ),
        timezoneId = TimeZone.currentSystemDefault().id,
        notificationOffsetMinutes = 1,
        constraints = ActionConstraints(
            requiresNetwork = false,
            requiresCharging = false,
        ),
    )
    return scheduler.registerAction(spec)
}

suspend fun scheduleMonthlySampleAction(scheduler: ActionScheduler): RegistrationResult {
    val runAtLocal = futureLocalDateTime(minutesFromNow = 3)
    val spec = ActionSpec(
        actionId = MONTHLY_ACTION_ID,
        actionType = ACTION_TYPE_MONTHLY_AUTOPAY,
        payloadJson = "{\"title\":\"Monthly auto-pay reminder\"}",
        recurrence = RecurrenceRule.Monthly(
            dayOfMonth = runAtLocal.dayOfMonth,
            hour = runAtLocal.hour,
            minute = runAtLocal.minute,
        ),
        timezoneId = TimeZone.currentSystemDefault().id,
        notificationOffsetMinutes = 2,
        constraints = ActionConstraints(
            requiresNetwork = true,
            requiresCharging = false,
        ),
    )
    return scheduler.registerAction(spec)
}

suspend fun cancelDailySampleAction(scheduler: ActionScheduler) {
    scheduler.cancelAction(DAILY_ACTION_ID)
}

suspend fun cancelMonthlySampleAction(scheduler: ActionScheduler) {
    scheduler.cancelAction(MONTHLY_ACTION_ID)
}

private fun futureLocalDateTime(minutesFromNow: Int): kotlinx.datetime.LocalDateTime {
    val nowMs = Clock.System.now().toEpochMilliseconds()
    val future = Instant.fromEpochMilliseconds(nowMs + minutesFromNow * 60_000L)
    return future.toLocalDateTime(TimeZone.currentSystemDefault())
}
