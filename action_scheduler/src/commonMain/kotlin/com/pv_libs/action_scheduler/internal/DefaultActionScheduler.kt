package com.pv_libs.action_scheduler.internal

import com.pv_libs.action_scheduler.ActionHandler
import com.pv_libs.action_scheduler.ActionHandlerResult
import com.pv_libs.action_scheduler.ActionNotification
import com.pv_libs.action_scheduler.ActionScheduler
import com.pv_libs.action_scheduler.ActionSchedulerConfig
import com.pv_libs.action_scheduler.ActionSchedulerKit
import com.pv_libs.action_scheduler.NotificationHandler
import com.pv_libs.action_scheduler.SDK_RUNNER_TASK_ID
import com.pv_libs.action_scheduler.SchedulerRoomStore
import com.pv_libs.action_scheduler.createPlatformSchedulerEngine
import com.pv_libs.action_scheduler.createSchedulerDatabase
import com.pv_libs.action_scheduler.internal.db.models.ActionExecutionEntity
import com.pv_libs.action_scheduler.logger
import com.pv_libs.action_scheduler.models.ActionConstraints
import com.pv_libs.action_scheduler.models.ActionInvocation
import com.pv_libs.action_scheduler.models.ActionSpec
import com.pv_libs.action_scheduler.models.ExecutionLog
import com.pv_libs.action_scheduler.models.RecurrenceRule
import com.pv_libs.action_scheduler.models.RegistrationResult
import com.pv_libs.action_scheduler.models.RunStatus
import com.pv_libs.action_scheduler.models.WorkerDispatchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Clock
import kotlin.time.Instant

private const val MAX_RETRIES = 3
private const val REMINDER_SUFFIX = "-reminder"
private val RETRY_SUFFIX_REGEX = Regex("-retry\\d+$")
private const val UNKNOWN_SCHEDULE_ID = "unknown"

internal class DefaultActionScheduler(
    private val config: ActionSchedulerConfig,
) : ActionScheduler {
    private val mutex = Mutex()
    private val coroutineScope = CoroutineScope(SupervisorJob())
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

    fun warmStart() {
        runBlocking {
            reconcileSingleRunner()
        }
    }

    override suspend fun registerAction(spec: ActionSpec): RegistrationResult {
        val result = mutex.withLock {
            if (!validate(spec)) return@withLock RegistrationResult.REJECTED_INVALID_PARAMS

            sqlStore.upsertSchedule(spec)
            scheduleNextForAction(spec, Clock.System.now())
            RegistrationResult.ACCEPTED
        }

        if (result == RegistrationResult.ACCEPTED) {
            reconcileSingleRunner()
        }

        return result
    }

    override suspend fun cancelAction(actionId: String): Unit {
        mutex.withLock {
            sqlStore.deleteSchedule(actionId)
            val pending = sqlStore.getPendingExecutions().filter { it.scheduleId == actionId }
            val endedAt = Clock.System.now()
            for (execution in pending) {
                sqlStore.upsertExecution(
                    execution.copy(
                        status = RunStatus.FAILED,
                        endedAt = endedAt,
                        errorMessage = "Canceled",
                    )
                )
            }
        }

        reconcileSingleRunner()
    }

    override fun getRegisteredActions(): Flow<List<ActionSpec>> = sqlStore.getAllSchedulesFlow()

    override fun getExecutionLogs(): Flow<List<ExecutionLog>> = sqlStore.getExecutionsListFlow()

    override fun registerHandler(actionType: String, handler: ActionHandler) {
        if (actionType.isBlank()) return
        handlers[actionType] = handler
    }

    override fun setNotificationHandler(handler: NotificationHandler?) {
        notificationHandler = handler
    }

    suspend fun dispatch(inputJson: String?): WorkerDispatchResult {
        try {
            if (inputJson == null) return WorkerDispatchResult(success = false, message = "No execution ID provided")

            val executionId = inputJson
            val execution = mutex.withLock { sqlStore.getExecution(executionId) }
            if (execution == null) {
                markExecutionNotFound(executionId)
                return WorkerDispatchResult(success = true, message = "Execution $executionId not found")
            }

            if (execution.status != RunStatus.PENDING) {
                return WorkerDispatchResult(success = true, message = "Execution already processed")
            }

            val schedule = mutex.withLock { sqlStore.getSchedule(execution.scheduleId) }
            if (schedule == null) {
                updateExecutionStatus(executionId, RunStatus.FAILED, errorMessage = "Schedule not found")
                return WorkerDispatchResult(success = false, message = "Schedule not found")
            }

            val startedAt = Clock.System.now()
            mutex.withLock {
                sqlStore.upsertExecution(execution.copy(status = RunStatus.RUNNING, startedAt = startedAt))
            }

            if (isReminderExecution(execution.id)) {
                return dispatchReminder(executionId = executionId, schedule = schedule, startedAt = startedAt)
            }

            val handler = handlers[schedule.actionType]
            if (handler == null) {
                updateExecutionStatus(executionId, RunStatus.HANDLER_NOT_FOUND, errorMessage = "No handler for ${schedule.actionType}", startedAt = startedAt)
                scheduleNextRecurrence(schedule)
                return WorkerDispatchResult(success = false, shouldRetry = false)
            }

            return try {
                when (
                    val result = handler.onExecute(
                        ActionInvocation(
                            actionId = schedule.actionId,
                            actionType = schedule.actionType,
                            payloadJson = schedule.payloadJson,
                            scheduledAt = execution.scheduledAt,
                        )
                    )
                ) {
                    ActionHandlerResult.Success -> {
                        updateExecutionStatus(executionId, RunStatus.SUCCESS, startedAt = startedAt)
                        scheduleNextRecurrence(schedule)
                        WorkerDispatchResult(success = true)
                    }

                    is ActionHandlerResult.Failure -> {
                        val attempt = execution.retryCount
                        val shouldRetry = result.retryable && attempt < MAX_RETRIES

                        if (shouldRetry) {
                            createRetryExecution(
                                execution = execution,
                                constraints = schedule.constraints,
                                errorCode = result.errorCode,
                                errorMessage = result.message,
                                startedAt = startedAt,
                            )
                        } else {
                            updateExecutionStatus(
                                executionId,
                                RunStatus.FAILED,
                                errorCode = result.errorCode,
                                errorMessage = result.message,
                                startedAt = startedAt,
                            )
                            scheduleNextRecurrence(schedule)
                        }

                        WorkerDispatchResult(
                            success = !shouldRetry,
                            shouldRetry = shouldRetry,
                            message = result.message,
                        )
                    }
                }
            } catch (t: Throwable) {
                val attempt = execution.retryCount
                val shouldRetry = attempt < MAX_RETRIES

                if (shouldRetry) {
                    createRetryExecution(
                        execution = execution,
                        constraints = schedule.constraints,
                        errorCode = "UNCAUGHT_EXCEPTION",
                        errorMessage = t.message ?: "Unhandled exception",
                        startedAt = startedAt,
                    )
                } else {
                    updateExecutionStatus(
                        executionId,
                        RunStatus.FAILED,
                        errorCode = "UNCAUGHT_EXCEPTION",
                        errorMessage = t.message ?: "Unhandled exception",
                        startedAt = startedAt,
                    )
                    scheduleNextRecurrence(schedule)
                }

                WorkerDispatchResult(success = !shouldRetry, shouldRetry = shouldRetry, message = t.message)
            }
        } finally {
            reconcileSingleRunner()
        }
    }

    private suspend fun updateExecutionStatus(
        executionId: String,
        status: RunStatus,
        errorCode: String? = null,
        errorMessage: String? = null,
        startedAt: Instant? = null
    ) = mutex.withLock {
        val execution = sqlStore.getExecution(executionId) ?: return@withLock
        val now = Clock.System.now()
        sqlStore.upsertExecution(
            execution.copy(
                status = status,
                startedAt = startedAt ?: execution.startedAt,
                endedAt = now,
                errorCode = errorCode,
                errorMessage = errorMessage
            )
        )
    }

    private suspend fun scheduleNextRecurrence(spec: ActionSpec) = mutex.withLock {
        if (spec.recurrence is RecurrenceRule.OneTime) {
            sqlStore.deleteSchedule(spec.actionId)
            return@withLock
        }

        val pending = sqlStore.getPendingExecutions().filter { it.scheduleId == spec.actionId }
        if (pending.isNotEmpty()) return@withLock

        scheduleNextForAction(spec, Clock.System.now())
    }

    private suspend fun scheduleNextForAction(
        spec: ActionSpec,
        from: Instant,
    ) {
        if (!spec.enabled) return

        val zone = runCatching { TimeZone.of(spec.timezoneId) }.getOrDefault(TimeZone.currentSystemDefault())
        val nextActionAt = nextOccurrence(spec.recurrence, from, zone) ?: return

        val executionId = "${spec.actionId}_${nextActionAt.toEpochMilliseconds()}"
        logger("scheduleNextForAction: $spec, \nfrom: $from - \nnextActionAt: $nextActionAt")
        val execution = ActionExecutionEntity(
            id = executionId,
            scheduleId = spec.actionId,
            scheduledAt = nextActionAt,
            startedAt = null,
            endedAt = null,
            status = RunStatus.PENDING,
            retryCount = 0
        )

        sqlStore.upsertExecution(execution)

        val reminderExecution = buildReminderExecution(mainExecution = execution, spec = spec)
        if (reminderExecution != null) {
            sqlStore.upsertExecution(reminderExecution)
        }
    }

    private fun buildReminderExecution(
        mainExecution: ActionExecutionEntity,
        spec: ActionSpec,
    ): ActionExecutionEntity? {
        val reminderOffsetMinutes = spec.notificationOffsetMinutes ?: return null
        val reminderAt = mainExecution.scheduledAt.minus(reminderOffsetMinutes.minutes)
        return ActionExecutionEntity(
            id = "${mainExecution.id}$REMINDER_SUFFIX",
            scheduleId = spec.actionId,
            scheduledAt = reminderAt,
            startedAt = null,
            endedAt = null,
            status = RunStatus.PENDING,
            retryCount = 0,
        )
    }

    private suspend fun dispatchReminder(
        executionId: String,
        schedule: ActionSpec,
        startedAt: Instant,
    ): WorkerDispatchResult {
        return try {
            notificationHandler?.onNotify(
                ActionNotification(
                    id = executionId,
                    title = schedule.notificationTitle ?: schedule.actionType,
                    message = schedule.notificationDescription ?: "Reminder for action ${schedule.actionId}",
                    payload = mapOf(
                        "actionId" to schedule.actionId,
                        "actionType" to schedule.actionType,
                        "executionId" to executionId,
                    ),
                )
            )
            updateExecutionStatus(executionId, RunStatus.SUCCESS, startedAt = startedAt)
            WorkerDispatchResult(success = true)
        } catch (t: Throwable) {
            updateExecutionStatus(
                executionId,
                RunStatus.FAILED,
                errorCode = "NOTIFICATION_EXCEPTION",
                errorMessage = t.message ?: "Notification dispatch failed",
                startedAt = startedAt,
            )
            WorkerDispatchResult(success = false, shouldRetry = false, message = t.message)
        }
    }

    private fun isReminderExecution(executionId: String): Boolean {
        return executionId.endsWith(REMINDER_SUFFIX)
    }

    private suspend fun createRetryExecution(
        execution: ActionExecutionEntity,
        constraints: ActionConstraints,
        errorCode: String,
        errorMessage: String,
        startedAt: Instant,
    ) {
        val nextAttempt = execution.retryCount + 1
        val nextDelay = nextBackoffDelay(constraints, execution.retryCount)
        val nextScheduledAt = Clock.System.now().plus(kotlin.time.Duration.parse("${nextDelay}ms"))
        val retryExecutionId = "${baseExecutionId(execution.id)}-retry$nextAttempt"
        val now = Clock.System.now()

        mutex.withLock {
            sqlStore.upsertExecution(
                execution.copy(
                    status = RunStatus.FAILED,
                    startedAt = startedAt,
                    endedAt = now,
                    errorCode = errorCode,
                    errorMessage = "Retrying as $retryExecutionId: $errorMessage",
                )
            )
            sqlStore.upsertExecution(
                ActionExecutionEntity(
                    id = retryExecutionId,
                    scheduleId = execution.scheduleId,
                    scheduledAt = nextScheduledAt,
                    startedAt = null,
                    endedAt = null,
                    status = RunStatus.PENDING,
                    retryCount = nextAttempt,
                    errorCode = null,
                    errorMessage = null,
                )
            )
        }
    }

    private suspend fun markExecutionNotFound(executionId: String) {
        val now = Clock.System.now()
        mutex.withLock {
            sqlStore.upsertExecution(
                ActionExecutionEntity(
                    id = executionId,
                    scheduleId = UNKNOWN_SCHEDULE_ID,
                    scheduledAt = now,
                    startedAt = now,
                    endedAt = now,
                    status = RunStatus.NOT_FOUND,
                    retryCount = 0,
                    errorCode = "EXECUTION_NOT_FOUND",
                    errorMessage = "Execution payload $executionId does not map to persisted execution",
                )
            )
        }
    }

    private suspend fun reconcileSingleRunner() {
        val target = mutex.withLock { findNearestPendingExecutionWithSpec() }
        if (target == null) {
            schedulerEngine.cancelRunner(SDK_RUNNER_TASK_ID)
            return
        }

        val (execution, spec) = target
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val delayMs = (execution.scheduledAt.toEpochMilliseconds() - nowMs).coerceAtLeast(0)

        schedulerEngine.scheduleRunner(
            runnerTaskId = SDK_RUNNER_TASK_ID,
            delayMs = delayMs,
            executionPayload = execution.id,
            constraints = spec.constraints,
        )
    }

    private suspend fun findNearestPendingExecutionWithSpec(): Pair<ActionExecutionEntity, ActionSpec>? {
        while (true) {
            val execution = sqlStore.getNearestPendingExecution() ?: return null
            val schedule = sqlStore.getSchedule(execution.scheduleId)

            if (schedule != null && schedule.enabled) {
                return execution to schedule
            }

            val now = Clock.System.now()
            val updatedStatus = if (schedule == null) RunStatus.NOT_FOUND else RunStatus.FAILED
            val updatedMessage = if (schedule == null) {
                "Schedule not found during reconciliation"
            } else {
                "Schedule disabled"
            }

            sqlStore.upsertExecution(
                execution.copy(
                    status = updatedStatus,
                    endedAt = now,
                    errorCode = if (schedule == null) "SCHEDULE_NOT_FOUND" else "SCHEDULE_DISABLED",
                    errorMessage = updatedMessage,
                )
            )
        }
    }

    private fun baseExecutionId(executionId: String): String {
        return executionId.replace(RETRY_SUFFIX_REGEX, "")
    }

    private fun validate(spec: ActionSpec): Boolean {
        if (spec.actionId.isBlank() || spec.actionType.isBlank()) return false
        if (spec.notificationOffsetMinutes != null && spec.notificationOffsetMinutes < 0) return false

        return when (val recurrence = spec.recurrence) {
            is RecurrenceRule.OneTime -> recurrence.at > Clock.System.now()
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
        return base * (1L shl attempt.coerceAtMost(10))
    }



    private fun nextOccurrence(rule: RecurrenceRule, from: Instant, timeZone: TimeZone): Instant? {
        return when (rule) {
            is RecurrenceRule.OneTime -> {
                val candidate = rule.at
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
                daysUntil += (rule.skipWeeks * 7) // skipping weeks to support bi-weekly, tri-weekly
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
                    monthNumber = nowLocal.month.number,
                    desiredDay = rule.dayOfMonth,
                )
                var candidate = LocalDateTime(candidateDate, time)

                if (candidate <= nowLocal) {
                    val nextMonthFirst = LocalDate(nowLocal.year, nowLocal.month.number, 1).plus(DatePeriod(months = 1))
                    candidateDate = clampToMonthDay(
                        year = nextMonthFirst.year,
                        monthNumber = nextMonthFirst.month.number,
                        desiredDay = rule.dayOfMonth,
                    )
                    candidate = LocalDateTime(candidateDate, time)
                }

                candidate.toInstant(timeZone)
            }
        }
    }

    private fun clampToMonthDay(year: Int, monthNumber: Int, desiredDay: Int): LocalDate {
        val monthStart = LocalDate(year = year, month = monthNumber, day = 1)
        val nextMonthStart = monthStart.plus(DatePeriod(months = 1))
        val lastDayOfMonth = nextMonthStart.minus(DatePeriod(days = 1)).day
        val resolvedDay = desiredDay.coerceIn(1, lastDayOfMonth)
        return LocalDate(year = year, month = monthNumber, day = resolvedDay)
    }

}