package com.pv_libs.sampleactionscheduler

import com.pv_libs.action_scheduler.ActionHandlerResult
import com.pv_libs.action_scheduler.ActionScheduler
import com.pv_libs.action_scheduler.logger
import com.pv_libs.action_scheduler.models.ActionConstraints
import com.pv_libs.action_scheduler.models.ActionSpec
import com.pv_libs.action_scheduler.models.RecurrenceRule
import com.pv_libs.action_scheduler.models.RegistrationResult
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes

const val DAILY_ACTION_ID = "daily_balance_nudge"
const val MONTHLY_ACTION_ID = "monthly_auto_pay_reminder"

private const val ACTION_TYPE_BALANCE_NUDGE = "BALANCE_NUDGE"
private const val ACTION_TYPE_MONTHLY_AUTOPAY = "MONTHLY_AUTOPAY_REMINDER"

fun registerSampleActionHandlers(scheduler: ActionScheduler) {
    scheduler.registerHandler(ACTION_TYPE_BALANCE_NUDGE) { invocation ->
        logger("ACTION_TYPE_BALANCE_NUDGE triggered - $invocation")
        ActionHandlerResult.Success
    }

    scheduler.registerHandler(ACTION_TYPE_MONTHLY_AUTOPAY) { invocation ->
        logger("ACTION_TYPE_MONTHLY_AUTOPAY triggered - $invocation")
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
    logger("scheduleDailySampleAction - $runAtLocal")
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
    val future = Clock.System.now() + minutesFromNow.minutes
    return future.toLocalDateTime(TimeZone.currentSystemDefault())
}
