package com.pv_libs.sampleactionscheduler

import com.pv_libs.action_scheduler.ActionHandlerResult
import com.pv_libs.action_scheduler.ActionNotification
import com.pv_libs.action_scheduler.ActionScheduler
import com.pv_libs.action_scheduler.NotificationHandler
import com.pv_libs.action_scheduler.logger
import com.pv_libs.action_scheduler.models.ActionConstraints
import com.pv_libs.action_scheduler.models.ActionSpec
import com.pv_libs.action_scheduler.models.RecurrenceRule
import com.pv_libs.action_scheduler.models.RegistrationResult
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

private const val ACTION_TYPE_CUSTOM_REMINDER = "CUSTOM_REMINDER"

enum class ReminderRecurrence {
    DAILY,
    WEEKLY,
    BI_WEEKLY,
    MONTHLY,
    ONE_TIME,
}

data class CustomReminderInput(
    val actionName: String,
    val recurrence: ReminderRecurrence,
    val notificationOffsetMinutes: Int?,
    val hour: Int,
    val minute: Int,
    val dayOfWeekIso: Int = 1,
    val dayOfMonth: Int = 1,
    val oneTimeYear: Int = 2026,
    val oneTimeMonth: Int = 1,
    val oneTimeDay: Int = 1,
)

fun registerSampleActionHandlers(scheduler: ActionScheduler, notificationHandler: NotificationHandler) {
    scheduler.registerHandler(ACTION_TYPE_CUSTOM_REMINDER) { invocation ->
        logger("ACTION_TYPE_CUSTOM_REMINDER triggered - $invocation")
        delay(2000)
        notificationHandler.onNotify(
            ActionNotification(
                invocation.actionId,
                "Reminder executed",
                invocation.toString(),
            )
        )
        val shouldFail = invocation.scheduledAt.toEpochMilliseconds() % 5L == 0L
        if (shouldFail) {
            ActionHandlerResult.Failure(
                message = "Simulated transient failure for reminder",
                retryable = true,
                errorCode = "SIMULATED_RETRYABLE",
            )
        } else {
            ActionHandlerResult.Success
        }
    }
}

suspend fun scheduleCustomAction(
    scheduler: ActionScheduler,
    input: CustomReminderInput,
): RegistrationResult {
    val actionName = input.actionName.trim()
    val recurrenceRule = when (input.recurrence) {
        ReminderRecurrence.DAILY -> RecurrenceRule.Daily(
            hour = input.hour,
            minute = input.minute,
        )

        ReminderRecurrence.WEEKLY,
        ReminderRecurrence.BI_WEEKLY,
        -> RecurrenceRule.Weekly(
            dayOfWeekIso = input.dayOfWeekIso,
            hour = input.hour,
            minute = input.minute,
        )

        ReminderRecurrence.MONTHLY -> RecurrenceRule.Monthly(
            dayOfMonth = input.dayOfMonth,
            hour = input.hour,
            minute = input.minute,
        )

        ReminderRecurrence.ONE_TIME -> {
            val dateTime = LocalDateTime(
                year = input.oneTimeYear,
                month = input.oneTimeMonth,
                day = input.oneTimeDay,
                hour = input.hour,
                minute = input.minute,
            )
            RecurrenceRule.OneTime(dateTime.toInstant(TimeZone.currentSystemDefault()))
        }
    }
    val payload = "{\"title\":\"$actionName\",\"recurrence\":\"${input.recurrence.name}\"}"
    val notificationText = "Reminder for $actionName at 5 PM"

    val spec = ActionSpec(
        actionId = actionName,
        actionType = ACTION_TYPE_CUSTOM_REMINDER,
        payloadJson = payload,
        recurrence = recurrenceRule,
        timezoneId = TimeZone.currentSystemDefault().id,
        notificationOffsetMinutes = input.notificationOffsetMinutes,
        notificationTitle = notificationText,
        notificationDescription = notificationText,
        constraints = ActionConstraints(),
    )
    return scheduler.registerAction(spec)
}

suspend fun cancelCustomAction(scheduler: ActionScheduler, actionName: String) {
    scheduler.cancelAction(actionName.trim())
}
