package com.pv_libs.action_scheduler.internal

import com.pv_libs.action_scheduler.ActionHandler
import com.pv_libs.action_scheduler.ActionHandlerResult
import com.pv_libs.action_scheduler.ActionNotification
import com.pv_libs.action_scheduler.ActionScheduler
import com.pv_libs.action_scheduler.ActionSchedulerConfig
import com.pv_libs.action_scheduler.ActionSchedulerKit
import com.pv_libs.action_scheduler.NotificationHandler
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

internal class DefaultActionScheduler(
    private val config: ActionSchedulerConfig,
) : ActionScheduler {
    private val mutex = Mutex()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        coroutineScope.launch {
            mutex.withLock {
                val pendingExecutions = sqlStore.getPendingExecutions()
                for (execution in pendingExecutions) {
                    val schedule = sqlStore.getAllSchedules().firstOrNull { it.actionId == execution.scheduleId }
                    if (schedule != null && schedule.enabled) {
                        scheduleWorker(execution, schedule)
                    }
                }
            }
        }
    }

    override suspend fun registerAction(spec: ActionSpec): RegistrationResult = mutex.withLock {
        if (!validate(spec)) return@withLock RegistrationResult.REJECTED_INVALID_PARAMS

        sqlStore.upsertSchedule(spec)
        scheduleNextForAction(spec, Clock.System.now())

        RegistrationResult.ACCEPTED
    }

    override suspend fun cancelAction(actionId: String): Unit = mutex.withLock {
        sqlStore.deleteSchedule(actionId)
        val pending = sqlStore.getPendingExecutions().filter { it.scheduleId == actionId }
        for (execution in pending) {
            schedulerEngine.cancelRunner(execution.id)
            sqlStore.upsertExecution(execution.copy(status = RunStatus.FAILED, errorMessage = "Canceled"))
        }
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
        if (inputJson == null) return WorkerDispatchResult(success = false, message = "No execution ID provided")

        val executionId = inputJson // We pass the executionId directly as the payload now

        val execution = mutex.withLock { sqlStore.getExecution(executionId) }
            ?: return WorkerDispatchResult(success = false, message = "Execution $executionId not found")

        if (execution.status != RunStatus.PENDING) {
            return WorkerDispatchResult(success = true, message = "Execution already processed")
        }

        val schedule = mutex.withLock { sqlStore.getAllSchedules().firstOrNull { it.actionId == execution.scheduleId } }
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

        // TODO: Handle Notifications based on execution metadata if needed.
        // For this refactor, we focus on the primary action execution.

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
                        val nextDelay = nextBackoffDelay(schedule.constraints, attempt)
                        val rescheduledAt = Clock.System.now().plus(kotlin.time.Duration.parse("${nextDelay}ms"))

                        // Reschedule existing execution
                        val updatedExecution = execution.copy(
                            scheduledAt = rescheduledAt,
                            status = RunStatus.PENDING,
                            retryCount = attempt + 1,
                            startedAt = null // Reset start time for next attempt
                        )
                        mutex.withLock { sqlStore.upsertExecution(updatedExecution) }
                        scheduleWorker(updatedExecution, schedule)
                    } else {
                        updateExecutionStatus(executionId, RunStatus.FAILED, errorCode = result.errorCode, errorMessage = result.message, startedAt = startedAt)
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
                val nextDelay = nextBackoffDelay(schedule.constraints, attempt)
                val rescheduledAt = Clock.System.now().plus(kotlin.time.Duration.parse("${nextDelay}ms"))

                val updatedExecution = execution.copy(
                    scheduledAt = rescheduledAt,
                    status = RunStatus.PENDING,
                    retryCount = attempt + 1,
                    startedAt = null
                )
                mutex.withLock { sqlStore.upsertExecution(updatedExecution) }
                scheduleWorker(updatedExecution, schedule)
            } else {
                updateExecutionStatus(executionId, RunStatus.FAILED, errorCode = "UNCAUGHT_EXCEPTION", errorMessage = t.message ?: "Unhandled exception", startedAt = startedAt)
                scheduleNextRecurrence(schedule)
            }

            WorkerDispatchResult(success = !shouldRetry, shouldRetry = shouldRetry, message = t.message)
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
        scheduleWorker(execution, spec)

        val reminderExecution = buildReminderExecution(mainExecution = execution, spec = spec)
        if (reminderExecution != null) {
            sqlStore.upsertExecution(reminderExecution)
            scheduleWorker(reminderExecution, spec)
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

    private suspend fun scheduleWorker(execution: ActionExecutionEntity, spec: ActionSpec) {
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val delayMs = (execution.scheduledAt.toEpochMilliseconds() - nowMs).coerceAtLeast(0)

        schedulerEngine.scheduleRunner(
            executionId = execution.id,
            delayMs = delayMs,
            inputJson = execution.id, // Pass executionId to worker
            constraints = spec.constraints,
        )
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
        return if (constraints.exponentialBackoff) {
            base * (1L shl attempt.coerceAtMost(10))
        } else {
            base
        }
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