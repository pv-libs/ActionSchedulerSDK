package com.pv_libs.action_scheduler

import android.content.Context
import androidx.room.Room
import com.pv_libs.action_scheduler.internal.db.SchedulerRoomDatabase
import com.pv_libs.action_scheduler.models.ActionConstraints
import com.pv_libs.action_scheduler.models.WorkerDispatchResult
import dev.brewkits.kmpworkmanager.KmpWorkManager
import dev.brewkits.kmpworkmanager.KmpWorkManagerConfig
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.BackoffPolicy
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult

private class AndroidSchedulerEngine(
    private val scheduler: BackgroundTaskScheduler,
) : SchedulerEngine {
    override suspend fun scheduleRunner(
        executionId: String,
        delayMs: Long,
        inputJson: String,
        constraints: ActionConstraints,
    ): Boolean {
        val scheduleResult = scheduler.enqueue(
            id = executionId,
            trigger = TaskTrigger.OneTime(initialDelayMs = delayMs.coerceAtLeast(0L)),
            workerClassName = SDK_WORKER_CLASS_NAME,
            constraints = Constraints(
                requiresNetwork = constraints.requiresNetwork,
                requiresCharging = constraints.requiresCharging,
                isHeavyTask = constraints.isHeavyTask,
                backoffPolicy = if (constraints.exponentialBackoff) {
                    BackoffPolicy.EXPONENTIAL
                } else {
                    BackoffPolicy.LINEAR
                },
                backoffDelayMs = constraints.backoffDelayMs,
            ),
            inputJson = inputJson,
            policy = ExistingPolicy.REPLACE,
        )

        return scheduleResult == ScheduleResult.ACCEPTED
    }

    override fun cancelRunner(executionId: String) {
        scheduler.cancel(executionId)
    }
}

private object AndroidDispatchWorkerFactory : AndroidWorkerFactory {
    @Volatile
    private var delegate: (suspend (String?) -> WorkerDispatchResult)? = null

    fun updateDelegate(dispatchWorker: suspend (String?) -> WorkerDispatchResult) {
        delegate = dispatchWorker
    }

    override fun createWorker(workerClassName: String): AndroidWorker? {
        if (workerClassName != SDK_WORKER_CLASS_NAME) return null
        return object : AndroidWorker {
            override suspend fun doWork(input: String?): WorkerResult {
                val executionDelegate = delegate
                    ?: return WorkerResult.Failure(
                        message = "ActionScheduler dispatch delegate is not set",
                        shouldRetry = true,
                    )

                val result = executionDelegate(input)
                return if (result.success) {
                    WorkerResult.Success(message = result.message)
                } else {
                    WorkerResult.Failure(
                        message = result.message ?: "ActionScheduler dispatch failed",
                        shouldRetry = result.shouldRetry,
                    )
                }
            }
        }
    }
}

internal actual fun createPlatformSchedulerEngine(
    dispatchWorker: suspend (String?) -> WorkerDispatchResult,
    config: ActionSchedulerConfig,
): SchedulerEngine {
    val context = config.platformContext as? Context
        ?: error("ActionSchedulerConfig.platformContext must be Android Context on Android")

    AndroidDispatchWorkerFactory.updateDelegate(dispatchWorker)

    if (!KmpWorkManager.isInitialized()) {
        KmpWorkManager.initialize(
            context = context.applicationContext,
            workerFactory = AndroidDispatchWorkerFactory,
            config = KmpWorkManagerConfig(),
        )
    }

    val scheduler = KmpWorkManager.getInstance().backgroundTaskScheduler
    return AndroidSchedulerEngine(scheduler)
}

internal actual fun createSchedulerDatabase(config: ActionSchedulerConfig): SchedulerRoomDatabase {
    val context = config.platformContext as? Context
        ?: error("ActionSchedulerConfig.platformContext must be Android Context on Android")
        
    val dbFile = context.getDatabasePath("${config.storageName}.db")
    return Room.databaseBuilder<SchedulerRoomDatabase>(
        context = context,
        name = dbFile.absolutePath
    ).fallbackToDestructiveMigration(true)
    .build()
}
