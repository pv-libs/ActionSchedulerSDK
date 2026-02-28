package com.pv_libs.sampleactionscheduler

import com.pv_libs.action_scheduler.ActionSchedulerConfig
import com.pv_libs.action_scheduler.ActionSchedulerKit

private const val IOS_RUNNER_TASK_ID = "com.pv_libs.action_scheduler.runner"

fun initializeSampleActionSchedulerIos() {
    initializeActionSchedulerIos(taskIds = listOf(IOS_RUNNER_TASK_ID))
}

fun initializeActionSchedulerIos(taskIds: List<String>) {
    val scheduler = ActionSchedulerKit.initialize(
        ActionSchedulerConfig(
            iosTaskIds = taskIds.toSet(),
        )
    )
    registerSampleActionHandlers(scheduler)
}
