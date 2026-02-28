package com.pv_libs.action_scheduler

import com.pv_libs.action_scheduler.db.ExecutionLogEntity
import com.pv_libs.action_scheduler.db.SchedulerRoomDatabase
import com.pv_libs.action_scheduler.db.SchedulerStateEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

private const val DEFAULT_STORAGE_NAME = "action_scheduler_sdk"
private const val DEFAULT_MAX_LOGS = 500
private const val SCHEDULER_STATE_ROW_ID = "scheduler_state"
internal const val SDK_RUNNER_TASK_ID = "com.pv_libs.action_scheduler.runner"
internal const val SDK_WORKER_CLASS_NAME = "ActionSchedulerDispatchWorker"
private const val MAX_RETRIES = 3

private val schedulerJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

/** Public API */
interface ActionScheduler {
    suspend fun registerAction(spec: ActionSpec): RegistrationResult
    suspend fun updateAction(spec: ActionSpec): RegistrationResult
    suspend fun cancelAction(actionId: String)
    suspend fun getRecentExecutions(
        actionId: String? = null,
        statuses: Set<RunStatus> = emptySet(),
        limit: Int = 50,
    ): List<ExecutionLog>

    suspend fun getRegisteredActions(): List<ActionSpec>

    fun registerHandler(actionType: String, handler: ActionHandler)
    fun setNotificationHandler(handler: NotificationHandler?)
}

@Serializable
data class ActionSpec(
    val actionId: String,
    val actionType: String,
    val payloadJson: String,
    val recurrence: RecurrenceRule,
    val timezoneId: String = TimeZone.currentSystemDefault().id,
    val notificationOffsetMinutes: Int? = null,
    val enabled: Boolean = true,
    val constraints: ActionConstraints = ActionConstraints(),
)

@Serializable
sealed interface RecurrenceRule {
    @Serializable
    @SerialName("one_time")
    data class OneTime(val atEpochMillis: Long) : RecurrenceRule

    @Serializable
    @SerialName("daily")
    data class Daily(val hour: Int, val minute: Int) : RecurrenceRule

    @Serializable
    @SerialName("weekly")
    data class Weekly(
        /** ISO day number: 1(Monday) .. 7(Sunday). */
        val dayOfWeekIso: Int,
        val hour: Int,
        val minute: Int,
    ) : RecurrenceRule

    @Serializable
    @SerialName("monthly")
    data class Monthly(
        /** 1..31, clamped to month length for short months. */
        val dayOfMonth: Int,
        val hour: Int,
        val minute: Int,
    ) : RecurrenceRule
}

@Serializable
data class ActionConstraints(
    val requiresNetwork: Boolean = false,
    val requiresCharging: Boolean = false,
    val isHeavyTask: Boolean = false,
    val backoffDelayMs: Long = 30_000,
    val exponentialBackoff: Boolean = true,
)

enum class RegistrationResult {
    ACCEPTED,
    REJECTED_INVALID_PARAMS,
    REJECTED_PLATFORM_POLICY,
    FAILED,
}

enum class RunStatus {
    SUCCESS,
    FAILED,
    DEDUPE_SKIPPED,
    NOTIFICATION_SENT,
    HANDLER_NOT_FOUND,
}

@Serializable
data class ExecutionLog(
    val runId: String,
    val actionId: String,
    val triggerId: String,
    val scheduledAtEpochMillis: Long,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val status: RunStatus,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val platformReason: String? = null,
)

data class ActionInvocation(
    val actionId: String,
    val actionType: String,
    val payloadJson: String,
    val scheduledAtEpochMillis: Long,
)

data class ActionNotification(
    val actionId: String,
    val actionType: String,
    val scheduledActionAtEpochMillis: Long,
)

sealed interface ActionHandlerResult {
    data object Success : ActionHandlerResult

    data class Failure(
        val message: String,
        val retryable: Boolean = true,
        val errorCode: String = "HANDLER_FAILURE",
    ) : ActionHandlerResult
}

fun interface ActionHandler {
    suspend fun onExecute(invocation: ActionInvocation): ActionHandlerResult
}

fun interface NotificationHandler {
    suspend fun onNotify(notification: ActionNotification)
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
    private val initLock = Any()

    @Volatile
    private var instance: DefaultActionScheduler? = null

    fun initialize(config: ActionSchedulerConfig = ActionSchedulerConfig()): ActionScheduler {
        instance?.let { return it }

        synchronized(initLock) {
            instance?.let { return it }

            val scheduler = DefaultActionScheduler(config)
            scheduler.warmStart()
            instance = scheduler
            return scheduler
        }
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

private class SchedulerRoomStore(
    database: SchedulerRoomDatabase,
    private val maxExecutionLogs: Int,
) {
    private val dao = database.schedulerDao()

    fun loadState(): PersistedSchedulerState {
        val raw = runBlocking { dao.getState(SCHEDULER_STATE_ROW_ID) }

        return raw
            ?.let { runCatching { schedulerJson.decodeFromString<PersistedSchedulerState>(it) }.getOrNull() }
            ?: PersistedSchedulerState()
    }

    fun saveState(state: PersistedSchedulerState) {
        runBlocking {
            dao.upsertState(
                SchedulerStateEntity(
                    id = SCHEDULER_STATE_ROW_ID,
                    stateJson = schedulerJson.encodeToString(state),
                )
            )
        }
    }

    fun insertLog(log: ExecutionLog) {
        runBlocking {
            dao.insertLog(
                ExecutionLogEntity(
                    runId = log.runId,
                    actionId = log.actionId,
                    triggerId = log.triggerId,
                    scheduledAtEpochMillis = log.scheduledAtEpochMillis,
                    startedAtEpochMillis = log.startedAtEpochMillis,
                    endedAtEpochMillis = log.endedAtEpochMillis,
                    status = log.status.name,
                    errorCode = log.errorCode,
                    errorMessage = log.errorMessage,
                    platformReason = log.platformReason,
                )
            )

            trimLogs(maxExecutionLogs.coerceAtLeast(20))
        }
    }

    fun getRecentLogs(
        actionId: String?,
        statuses: Set<RunStatus>,
        limit: Int,
    ): List<ExecutionLog> {
        val effectiveLimit = limit.coerceAtLeast(1).toLong()
        val rows = runBlocking {
            when {
                actionId != null && statuses.isNotEmpty() ->
                    dao.selectRecentByActionAndStatuses(
                        actionId = actionId,
                        statuses = statuses.map { it.name },
                        limit = effectiveLimit,
                    )

                actionId != null ->
                    dao.selectRecentByAction(
                        actionId = actionId,
                        limit = effectiveLimit,
                    )

                statuses.isNotEmpty() ->
                    dao.selectRecentByStatuses(
                        statuses = statuses.map { it.name },
                        limit = effectiveLimit,
                    )

                else -> dao.selectRecentAll(limit = effectiveLimit)
            }
        }

        return rows.map { row ->
            val status = runCatching { RunStatus.valueOf(row.status) }.getOrDefault(RunStatus.FAILED)
            ExecutionLog(
                runId = row.runId,
                actionId = row.actionId,
                triggerId = row.triggerId,
                scheduledAtEpochMillis = row.scheduledAtEpochMillis,
                startedAtEpochMillis = row.startedAtEpochMillis,
                endedAtEpochMillis = row.endedAtEpochMillis,
                status = status,
                errorCode = row.errorCode,
                errorMessage = row.errorMessage,
                platformReason = row.platformReason,
            )
        }
    }

    private suspend fun trimLogs(maxEntries: Int) {
        dao.deleteOverflow(keep = maxEntries.toLong())
    }
}

@Serializable
private data class PendingTrigger(
    val triggerId: String,
    val actionId: String,
    val scheduledAtEpochMillis: Long,
    val kind: TriggerKind,
    val state: TriggerState = TriggerState.SCHEDULED,
    val attempt: Int = 0,
)

@Serializable
private enum class TriggerKind {
    ACTION,
    PRE_NOTIFICATION,
}

@Serializable
private enum class TriggerState {
    SCHEDULED,
    RUNNING,
    COMPLETED,
    FAILED,
}

@Serializable
private data class PersistedSchedulerState(
    val actions: List<ActionSpec> = emptyList(),
    val pending: List<PendingTrigger> = emptyList(),
)

@Serializable
private data class RunnerInput(
    val reason: String,
    val enqueueAtEpochMillis: Long,
)

internal data class WorkerDispatchResult(
    val success: Boolean,
    val shouldRetry: Boolean = false,
    val message: String? = null,
)

private class DefaultActionScheduler(
    private val config: ActionSchedulerConfig,
) : ActionScheduler {
    private val mutex = Mutex()
    private val sqlStore = SchedulerRoomStore(
        database = createSchedulerDatabase(config),
        maxExecutionLogs = config.maxExecutionLogs,
    )

    private val handlers = mutableMapOf<String, ActionHandler>()
    private var notificationHandler: NotificationHandler? = null

    private val schedulerEngine = createPlatformSchedulerEngine(
        dispatchWorker = { payload -> ActionSchedulerKit.dispatchFromWorker(payload) },
        config = config,
    )

    private var state: PersistedSchedulerState = loadState()

    fun warmStart() {
        runBlocking {
            mutex.withLock {
                val repaired = state.pending.map {
                    if (it.state == TriggerState.RUNNING) {
                        it.copy(state = TriggerState.SCHEDULED)
                    } else {
                        it
                    }
                }
                state = state.copy(pending = repaired)
                saveState()
                rescheduleRunnerLocked("warm_start")
            }
        }
    }

    override suspend fun registerAction(spec: ActionSpec): RegistrationResult = mutex.withLock {
        if (!validate(spec)) return@withLock RegistrationResult.REJECTED_INVALID_PARAMS

        val withoutExisting = state.actions.filterNot { it.actionId == spec.actionId }
        val withoutPending = state.pending.filterNot { it.actionId == spec.actionId && it.state == TriggerState.SCHEDULED }

        var updatedState = state.copy(actions = withoutExisting + spec, pending = withoutPending)
        updatedState = scheduleNextForActionLocked(updatedState, spec, Clock.System.now())

        state = updatedState
        saveState()

        val scheduled = rescheduleRunnerLocked("register_action")
        if (!scheduled) RegistrationResult.REJECTED_PLATFORM_POLICY else RegistrationResult.ACCEPTED
    }

    override suspend fun updateAction(spec: ActionSpec): RegistrationResult {
        return registerAction(spec)
    }

    override suspend fun cancelAction(actionId: String): Unit = mutex.withLock {
        state = state.copy(
            actions = state.actions.filterNot { it.actionId == actionId },
            pending = state.pending.filterNot { it.actionId == actionId },
        )
        saveState()
        rescheduleRunnerLocked("cancel_action")
    }

    override suspend fun getRecentExecutions(
        actionId: String?,
        statuses: Set<RunStatus>,
        limit: Int,
    ): List<ExecutionLog> = mutex.withLock {
        sqlStore.getRecentLogs(
            actionId = actionId,
            statuses = statuses,
            limit = limit,
        )
    }

    override suspend fun getRegisteredActions(): List<ActionSpec> = mutex.withLock {
        state.actions.sortedBy { it.actionId }
    }

    override fun registerHandler(actionType: String, handler: ActionHandler) {
        if (actionType.isBlank()) return
        handlers[actionType] = handler
    }

    override fun setNotificationHandler(handler: NotificationHandler?) {
        notificationHandler = handler
    }

    suspend fun dispatch(inputJson: String?): WorkerDispatchResult {
        val dispatchAt = Clock.System.now().toEpochMilliseconds()

        val dueTriggers: List<PendingTrigger> = mutex.withLock {
            val due = state.pending
                .filter { it.state == TriggerState.SCHEDULED && it.scheduledAtEpochMillis <= dispatchAt }
                .sortedBy { it.scheduledAtEpochMillis }

            if (due.isEmpty()) {
                rescheduleRunnerLocked("worker_no_due")
                return WorkerDispatchResult(success = true, message = "No due triggers")
            }

            val dueIds = due.map { it.triggerId }.toSet()
            state = state.copy(
                pending = state.pending.map { pending ->
                    if (pending.triggerId in dueIds) {
                        pending.copy(state = TriggerState.RUNNING)
                    } else {
                        pending
                    }
                }
            )
            saveState()
            due
        }

        var processed = 0
        var requestedRetry = false

        for (trigger in dueTriggers) {
            val actionSpec = mutex.withLock { state.actions.firstOrNull { it.actionId == trigger.actionId } }
            if (actionSpec == null) {
                finalizeTrigger(trigger, TriggerState.FAILED)
                appendLog(
                    trigger = trigger,
                    status = RunStatus.FAILED,
                    errorCode = "ACTION_NOT_FOUND",
                    errorMessage = "Action '${trigger.actionId}' is not registered",
                )
                continue
            }

            val result = when (trigger.kind) {
                TriggerKind.PRE_NOTIFICATION -> executeNotification(trigger, actionSpec)
                TriggerKind.ACTION -> executeAction(trigger, actionSpec)
            }

            processed += 1
            requestedRetry = requestedRetry || result.shouldRetry
        }

        mutex.withLock {
            saveState()
            rescheduleRunnerLocked("worker_processed")
        }

        return WorkerDispatchResult(
            success = !requestedRetry,
            shouldRetry = requestedRetry,
            message = "Processed $processed due trigger(s)",
        )
    }

    private suspend fun executeNotification(trigger: PendingTrigger, spec: ActionSpec): WorkerDispatchResult {
        val startedAt = Clock.System.now().toEpochMilliseconds()
        logger("executeNotification triggered - $spec, $trigger")
        return try {
            notificationHandler?.onNotify(
                ActionNotification(
                    actionId = spec.actionId,
                    actionType = spec.actionType,
                    scheduledActionAtEpochMillis = trigger.scheduledAtEpochMillis +
                        ((spec.notificationOffsetMinutes ?: 0).toLong() * 60_000L),
                )
            )

            finalizeTrigger(trigger, TriggerState.COMPLETED)
            appendLog(
                trigger = trigger,
                status = RunStatus.NOTIFICATION_SENT,
                startedAtEpochMillisOverride = startedAt,
            )
            WorkerDispatchResult(success = true)
        } catch (t: Throwable) {
            finalizeTrigger(trigger, TriggerState.FAILED)
            appendLog(
                trigger = trigger,
                status = RunStatus.FAILED,
                errorCode = "NOTIFICATION_FAILURE",
                errorMessage = t.message ?: "Unknown notification error",
                startedAtEpochMillisOverride = startedAt,
            )
            WorkerDispatchResult(success = false, shouldRetry = false, message = t.message)
        }
    }

    private suspend fun executeAction(trigger: PendingTrigger, spec: ActionSpec): WorkerDispatchResult {
        val startedAt = Clock.System.now().toEpochMilliseconds()
        val handler = handlers[spec.actionType]

        if (handler == null) {
            finalizeTrigger(trigger, TriggerState.FAILED)
            appendLog(
                trigger = trigger,
                status = RunStatus.HANDLER_NOT_FOUND,
                errorCode = "HANDLER_NOT_FOUND",
                errorMessage = "No handler for actionType='${spec.actionType}'",
                startedAtEpochMillisOverride = startedAt,
            )
            scheduleNextRecurrence(spec)
            return WorkerDispatchResult(success = false, shouldRetry = false)
        }

        return try {
            when (
                val result = handler.onExecute(
                    ActionInvocation(
                        actionId = spec.actionId,
                        actionType = spec.actionType,
                        payloadJson = spec.payloadJson,
                        scheduledAtEpochMillis = trigger.scheduledAtEpochMillis,
                    )
                )
            ) {
                ActionHandlerResult.Success -> {
                    finalizeTrigger(trigger, TriggerState.COMPLETED)
                    appendLog(
                        trigger = trigger,
                        status = RunStatus.SUCCESS,
                        startedAtEpochMillisOverride = startedAt,
                    )
                    scheduleNextRecurrence(spec)
                    WorkerDispatchResult(success = true)
                }

                is ActionHandlerResult.Failure -> {
                    val shouldRetry = result.retryable && trigger.attempt < MAX_RETRIES
                    if (shouldRetry) {
                        val nextDelay = nextBackoffDelay(spec.constraints, trigger.attempt)
                        val rescheduledAt = Clock.System.now().toEpochMilliseconds() + nextDelay
                        rescheduleTrigger(trigger, rescheduledAt)
                    } else {
                        finalizeTrigger(trigger, TriggerState.FAILED)
                        scheduleNextRecurrence(spec)
                    }

                    appendLog(
                        trigger = trigger,
                        status = RunStatus.FAILED,
                        errorCode = result.errorCode,
                        errorMessage = result.message,
                        startedAtEpochMillisOverride = startedAt,
                    )

                    WorkerDispatchResult(
                        success = !shouldRetry,
                        shouldRetry = shouldRetry,
                        message = result.message,
                    )
                }
            }
        } catch (t: Throwable) {
            val shouldRetry = trigger.attempt < MAX_RETRIES
            if (shouldRetry) {
                val nextDelay = nextBackoffDelay(spec.constraints, trigger.attempt)
                val rescheduledAt = Clock.System.now().toEpochMilliseconds() + nextDelay
                rescheduleTrigger(trigger, rescheduledAt)
            } else {
                finalizeTrigger(trigger, TriggerState.FAILED)
                scheduleNextRecurrence(spec)
            }

            appendLog(
                trigger = trigger,
                status = RunStatus.FAILED,
                errorCode = "UNCAUGHT_EXCEPTION",
                errorMessage = t.message ?: "Unhandled action exception",
                startedAtEpochMillisOverride = startedAt,
            )

            WorkerDispatchResult(success = !shouldRetry, shouldRetry = shouldRetry, message = t.message)
        }
    }

    private suspend fun finalizeTrigger(trigger: PendingTrigger, finalState: TriggerState) = mutex.withLock {
        state = state.copy(
            pending = state.pending.map {
                if (it.triggerId == trigger.triggerId) it.copy(state = finalState) else it
            }
        )
    }

    private suspend fun rescheduleTrigger(trigger: PendingTrigger, nextAtEpochMillis: Long) = mutex.withLock {
        state = state.copy(
            pending = state.pending.map {
                if (it.triggerId == trigger.triggerId) {
                    it.copy(
                        scheduledAtEpochMillis = nextAtEpochMillis,
                        state = TriggerState.SCHEDULED,
                        attempt = it.attempt + 1,
                    )
                } else {
                    it
                }
            }
        )
    }

    private suspend fun appendLog(
        trigger: PendingTrigger,
        status: RunStatus,
        errorCode: String? = null,
        errorMessage: String? = null,
        startedAtEpochMillisOverride: Long? = null,
    ) = mutex.withLock {
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val log = ExecutionLog(
            runId = "${trigger.triggerId}:$nowMs",
            actionId = trigger.actionId,
            triggerId = trigger.triggerId,
            scheduledAtEpochMillis = trigger.scheduledAtEpochMillis,
            startedAtEpochMillis = startedAtEpochMillisOverride ?: nowMs,
            endedAtEpochMillis = nowMs,
            status = status,
            errorCode = errorCode,
            errorMessage = errorMessage,
        )
        sqlStore.insertLog(log)
    }

    private suspend fun scheduleNextRecurrence(spec: ActionSpec) = mutex.withLock {
        val now = Clock.System.now()

        if (spec.recurrence is RecurrenceRule.OneTime) {
            state = state.copy(actions = state.actions.filterNot { it.actionId == spec.actionId })
            return@withLock
        }

        val alreadyHasScheduledAction = state.pending.any {
            it.actionId == spec.actionId && it.kind == TriggerKind.ACTION && it.state == TriggerState.SCHEDULED
        }
        if (alreadyHasScheduledAction) return@withLock

        state = scheduleNextForActionLocked(state, spec, now)
    }

    private fun scheduleNextForActionLocked(
        base: PersistedSchedulerState,
        spec: ActionSpec,
        from: Instant,
    ): PersistedSchedulerState {
        if (!spec.enabled) return base

        val zone = runCatching { TimeZone.of(spec.timezoneId) }.getOrDefault(TimeZone.currentSystemDefault())
        val nextActionAt = nextOccurrence(spec.recurrence, from, zone) ?: return base

        val actionTrigger = PendingTrigger(
            triggerId = triggerId(spec.actionId, TriggerKind.ACTION, nextActionAt.toEpochMilliseconds()),
            actionId = spec.actionId,
            scheduledAtEpochMillis = nextActionAt.toEpochMilliseconds(),
            kind = TriggerKind.ACTION,
        )

        val generated = mutableListOf(actionTrigger)
        spec.notificationOffsetMinutes?.takeIf { it > 0 }?.let { offsetMin ->
            val notificationAtMs = nextActionAt.toEpochMilliseconds() - (offsetMin.toLong() * 60_000L)
            if (notificationAtMs > from.toEpochMilliseconds()) {
                generated += PendingTrigger(
                    triggerId = triggerId(spec.actionId, TriggerKind.PRE_NOTIFICATION, notificationAtMs),
                    actionId = spec.actionId,
                    scheduledAtEpochMillis = notificationAtMs,
                    kind = TriggerKind.PRE_NOTIFICATION,
                )
            }
        }

        val existingIds = base.pending.map { it.triggerId }.toSet()
        val mergedPending = base.pending + generated.filterNot { it.triggerId in existingIds }
        return base.copy(pending = mergedPending)
    }

    private suspend fun rescheduleRunnerLocked(reason: String): Boolean {
        val nextPending = state.pending
            .filter { it.state == TriggerState.SCHEDULED }
            .minByOrNull { it.scheduledAtEpochMillis }

        if (nextPending == null) {
            schedulerEngine.cancelRunner()
            return true
        }

        val nowMs = Clock.System.now().toEpochMilliseconds()
        val delayMs = (nextPending.scheduledAtEpochMillis - nowMs).coerceAtLeast(0)

        val action = state.actions.firstOrNull { it.actionId == nextPending.actionId }
        val constraints = action?.constraints ?: ActionConstraints()

        val payloadJson = schedulerJson.encodeToString(
            RunnerInput(
                reason = reason,
                enqueueAtEpochMillis = nowMs,
            )
        )

        return schedulerEngine.scheduleRunner(
            delayMs = delayMs,
            inputJson = payloadJson,
            constraints = constraints,
        )
    }

    private fun loadState(): PersistedSchedulerState {
        return sqlStore.loadState()
    }

    private fun saveState() {
        sqlStore.saveState(state)
    }

    private fun validate(spec: ActionSpec): Boolean {
        if (spec.actionId.isBlank() || spec.actionType.isBlank()) return false
        if (spec.notificationOffsetMinutes != null && spec.notificationOffsetMinutes < 0) return false

        return when (val recurrence = spec.recurrence) {
            is RecurrenceRule.OneTime -> recurrence.atEpochMillis > Clock.System.now().toEpochMilliseconds()
            is RecurrenceRule.Daily -> isValidHourMinute(recurrence.hour, recurrence.minute)
            is RecurrenceRule.Weekly -> {
                isValidHourMinute(recurrence.hour, recurrence.minute) && recurrence.dayOfWeekIso in 1..7
            }

            is RecurrenceRule.Monthly -> {
                isValidHourMinute(recurrence.hour, recurrence.minute) && recurrence.dayOfMonth in 1..31
            }
        }
    }

    private fun isValidHourMinute(hour: Int, minute: Int): Boolean {
        return hour in 0..23 && minute in 0..59
    }

    private fun nextBackoffDelay(constraints: ActionConstraints, attempt: Int): Long {
        val base = constraints.backoffDelayMs.coerceAtLeast(5_000)
        return if (constraints.exponentialBackoff) {
            base * (1L shl attempt.coerceAtMost(10))
        } else {
            base
        }
    }
}

private fun triggerId(actionId: String, kind: TriggerKind, scheduledAtEpochMillis: Long): String {
    return "$actionId:${kind.name}:$scheduledAtEpochMillis"
}

private fun nextOccurrence(rule: RecurrenceRule, from: Instant, timeZone: TimeZone): Instant? {
    return when (rule) {
        is RecurrenceRule.OneTime -> {
            val candidate = Instant.fromEpochMilliseconds(rule.atEpochMillis)
            if (candidate > from) candidate else null
        }

        is RecurrenceRule.Daily -> {
            val nowLocal = from.toLocalDateTime(timeZone)
            val time = LocalTime(rule.hour, rule.minute)
            var candidate = LocalDateTime(nowLocal.date, time)
            if (candidate <= nowLocal) {
                candidate = LocalDateTime(nowLocal.date.plus(DatePeriod(days = 1)), time)
            }
            candidate.toInstant(timeZone)
        }

        is RecurrenceRule.Weekly -> {
            val nowLocal = from.toLocalDateTime(timeZone)
            val targetDay = DayOfWeek(rule.dayOfWeekIso)
            val time = LocalTime(rule.hour, rule.minute)

            var daysUntil = targetDay.isoDayNumber - nowLocal.date.dayOfWeek.isoDayNumber
            if (daysUntil < 0) daysUntil += 7

            var candidateDate = nowLocal.date.plus(DatePeriod(days = daysUntil))
            var candidate = LocalDateTime(candidateDate, time)

            if (candidate <= nowLocal) {
                candidateDate = candidateDate.plus(DatePeriod(days = 7))
                candidate = LocalDateTime(candidateDate, time)
            }

            candidate.toInstant(timeZone)
        }

        is RecurrenceRule.Monthly -> {
            val nowLocal = from.toLocalDateTime(timeZone)
            val time = LocalTime(rule.hour, rule.minute)

            var candidateDate = clampToMonthDay(
                year = nowLocal.year,
                monthNumber = nowLocal.monthNumber,
                desiredDay = rule.dayOfMonth,
            )
            var candidate = LocalDateTime(candidateDate, time)

            if (candidate <= nowLocal) {
                val nextMonthFirst = LocalDate(nowLocal.year, nowLocal.monthNumber, 1).plus(DatePeriod(months = 1))
                candidateDate = clampToMonthDay(
                    year = nextMonthFirst.year,
                    monthNumber = nextMonthFirst.monthNumber,
                    desiredDay = rule.dayOfMonth,
                )
                candidate = LocalDateTime(candidateDate, time)
            }

            candidate.toInstant(timeZone)
        }
    }
}

private fun clampToMonthDay(year: Int, monthNumber: Int, desiredDay: Int): LocalDate {
    val monthStart = LocalDate(year = year, monthNumber = monthNumber, dayOfMonth = 1)
    val nextMonthStart = monthStart.plus(DatePeriod(months = 1))
    val lastDayOfMonth = nextMonthStart.minus(DatePeriod(days = 1)).dayOfMonth
    val resolvedDay = desiredDay.coerceIn(1, lastDayOfMonth)
    return LocalDate(year = year, monthNumber = monthNumber, dayOfMonth = resolvedDay)
}

internal interface SchedulerEngine {
    suspend fun scheduleRunner(
        delayMs: Long,
        inputJson: String,
        constraints: ActionConstraints,
    ): Boolean

    fun cancelRunner()
}

internal expect fun createPlatformSchedulerEngine(
    dispatchWorker: suspend (String?) -> WorkerDispatchResult,
    config: ActionSchedulerConfig,
): SchedulerEngine

internal expect fun createSchedulerDatabase(config: ActionSchedulerConfig): SchedulerRoomDatabase
