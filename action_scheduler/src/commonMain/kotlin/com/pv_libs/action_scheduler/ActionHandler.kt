package com.pv_libs.action_scheduler

import com.pv_libs.action_scheduler.models.ActionInvocation


fun interface ActionHandler {
    suspend fun onExecute(invocation: ActionInvocation): ActionHandlerResult
}
