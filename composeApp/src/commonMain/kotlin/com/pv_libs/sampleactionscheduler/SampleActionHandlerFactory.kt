package com.pv_libs.sampleactionscheduler

import com.pv_libs.action_scheduler.ActionHandler
import com.pv_libs.action_scheduler.ActionHandlerFactory
import com.pv_libs.action_scheduler.ActionHandlerResult
import com.pv_libs.action_scheduler.ActionNotification
import com.pv_libs.action_scheduler.NotificationHandler
import com.pv_libs.action_scheduler.logger
import com.pv_libs.action_scheduler.models.ActionInvocation
import kotlinx.coroutines.delay

class SampleActionHandlerFactory(
    private val notificationHandler: NotificationHandler
) : ActionHandlerFactory {
    override fun create(actionType: String): ActionHandler? {
        return when (actionType) {
            ACTION_TYPE_CUSTOM_REMINDER -> {
                ActionReminderHandler(notificationHandler)
            }

            ACTION_TYPE_DIGI_GOLD -> {
                DigiGoldHandler()
            }

            else -> null
        }
    }
}

private class ActionReminderHandler(private val notificationHandler: NotificationHandler) :
    ActionHandler {
    override suspend fun onExecute(invocation: ActionInvocation): ActionHandlerResult {
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
        return if (shouldFail) {
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

private class DigiGoldHandler : ActionHandler {
    override suspend fun onExecute(invocation: ActionInvocation): ActionHandlerResult {
        return ActionHandlerResult.Success
    }

}