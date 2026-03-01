package com.pv_libs.action_scheduler

import com.pv_libs.action_scheduler.internal.db.SchedulerRoomDatabase
import com.pv_libs.action_scheduler.models.ActionConstraints
import com.pv_libs.action_scheduler.models.WorkerDispatchResult

internal interface SchedulerEngine {
    suspend fun scheduleRunner(
        executionId: String,
        delayMs: Long,
        inputJson: String,
        constraints: ActionConstraints,
    ): Boolean

    fun cancelRunner(executionId: String)
}

internal expect fun createPlatformSchedulerEngine(
    dispatchWorker: suspend (String?) -> WorkerDispatchResult,
    config: ActionSchedulerConfig,
): SchedulerEngine

internal expect fun createSchedulerDatabase(config: ActionSchedulerConfig): SchedulerRoomDatabase