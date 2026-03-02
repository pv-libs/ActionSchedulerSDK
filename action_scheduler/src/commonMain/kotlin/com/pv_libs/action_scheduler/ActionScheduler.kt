package com.pv_libs.action_scheduler

import com.pv_libs.action_scheduler.internal.DefaultActionScheduler
import com.pv_libs.action_scheduler.models.ActionSpec
import com.pv_libs.action_scheduler.models.ExecutionLog
import com.pv_libs.action_scheduler.models.RegistrationResult
import com.pv_libs.action_scheduler.models.RunStatus
import com.pv_libs.action_scheduler.models.WorkerDispatchResult
import kotlinx.coroutines.flow.Flow
import org.koin.mp.KoinPlatformTools.synchronized
import org.koin.mp.Lockable
import kotlin.concurrent.Volatile

private const val DEFAULT_STORAGE_NAME = "action_scheduler_sdk"
private const val DEFAULT_MAX_LOGS = 500
internal const val SDK_RUNNER_TASK_ID = "com.pv_libs.action_scheduler.runner"
internal const val SDK_WORKER_CLASS_NAME = "ActionSchedulerDispatchWorker"




/** Public API */
interface ActionScheduler {
    suspend fun registerAction(spec: ActionSpec): RegistrationResult
    suspend fun cancelAction(actionId: String)

    fun getRegisteredActions(): Flow<List<ActionSpec>>
    fun getExecutionLogs(): Flow<List<ExecutionLog>>

    fun registerHandler(actionType: String, handler: ActionHandler)
    fun setNotificationHandler(handler: NotificationHandler?)
}


sealed interface ActionHandlerResult {
    data object Success : ActionHandlerResult

    data class Failure(
        val message: String,
        val retryable: Boolean = true,
        val errorCode: String = "HANDLER_FAILURE",
    ) : ActionHandlerResult
}

data class ActionSchedulerConfig(
    val storageName: String = DEFAULT_STORAGE_NAME,
    val maxExecutionLogs: Int = DEFAULT_MAX_LOGS,
    /** Android: pass Application context. iOS: ignored. */
    val platformContext: Any? = null,
    /** iOS BGTask identifiers (must exist in Info.plist). */
    val iosTaskIds: Set<String> = setOf(SDK_RUNNER_TASK_ID),
)

object ActionSchedulerKit {

    @Volatile
    private var instance: DefaultActionScheduler? = null

    fun initialize(config: ActionSchedulerConfig = ActionSchedulerConfig()): ActionScheduler {
        instance?.let { return it }
        val scheduler = DefaultActionScheduler(config)
        scheduler.warmStart()
        instance = scheduler

        return scheduler
    }

    fun getOrNull(): ActionScheduler? = instance

    internal suspend fun dispatchFromWorker(inputJson: String?): WorkerDispatchResult {
        val scheduler = instance ?: return WorkerDispatchResult(
            success = false,
            shouldRetry = true,
            message = "ActionSchedulerKit is not initialized",
        )
        return scheduler.dispatch(inputJson).apply {
            logger("dispatchFromWorker - $this")
        }
    }
}
