package com.pv_libs.action_scheduler

import androidx.room.Room
import com.pv_libs.action_scheduler.internal.db.SchedulerRoomDatabase
import com.pv_libs.action_scheduler.models.ActionConstraints
import com.pv_libs.action_scheduler.models.WorkerDispatchResult
import dev.brewkits.kmpworkmanager.KmpWorkManagerConfig
import dev.brewkits.kmpworkmanager.background.data.IosWorker
import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.BackoffPolicy
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.ExistingPolicy
import dev.brewkits.kmpworkmanager.background.domain.ScheduleResult
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.kmpWorkerModule
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.module.Module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import kotlin.concurrent.Volatile

private class IosSchedulerEngine(
    private val scheduler: BackgroundTaskScheduler,
) : SchedulerEngine {
    override suspend fun scheduleRunner(
        executionId: String,
        delayMs: Long,
        inputJson: String,
        constraints: ActionConstraints,
    ): Boolean {
        val result = scheduler.enqueue(
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

        return result == ScheduleResult.ACCEPTED
    }

    override fun cancelRunner(executionId: String) {
        scheduler.cancel(executionId)
    }
}

private object IosDispatchWorkerFactory : IosWorkerFactory {
    @Volatile
    private var delegate: (suspend (String?) -> WorkerDispatchResult)? = null

    fun updateDelegate(dispatchWorker: suspend (String?) -> WorkerDispatchResult) {
        delegate = dispatchWorker
    }

    override fun createWorker(workerClassName: String): IosWorker? {
        if (workerClassName != SDK_WORKER_CLASS_NAME) return null

        return object : IosWorker {
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
    IosDispatchWorkerFactory.updateDelegate(dispatchWorker)

    val module: Module = kmpWorkerModule(
        workerFactory = IosDispatchWorkerFactory,
        config = KmpWorkManagerConfig(),
        iosTaskIds = config.iosTaskIds,
    )



    return IosSchedulerEngine(NativeTaskScheduler())
}

@OptIn(ExperimentalForeignApi::class)
private fun documentDirectory(): String {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(documentDirectory?.path)
}

internal actual fun createSchedulerDatabase(config: ActionSchedulerConfig): SchedulerRoomDatabase {
    val dbFilePath = documentDirectory() + "/${config.storageName}.db"
    return Room.databaseBuilder<SchedulerRoomDatabase>(
        name = dbFilePath,
    )
        .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver())
     .fallbackToDestructiveMigration(true)
     .build()
}
