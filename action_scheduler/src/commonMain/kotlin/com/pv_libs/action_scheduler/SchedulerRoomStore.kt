package com.pv_libs.action_scheduler

import com.pv_libs.action_scheduler.internal.db.SchedulerRoomDatabase
import com.pv_libs.action_scheduler.internal.db.models.ActionExecutionEntity
import com.pv_libs.action_scheduler.internal.db.models.ActionScheduleEntity
import com.pv_libs.action_scheduler.models.ActionConstraints
import com.pv_libs.action_scheduler.models.ActionSpec
import com.pv_libs.action_scheduler.models.ExecutionLog
import com.pv_libs.action_scheduler.models.RecurrenceRule
import com.pv_libs.action_scheduler.models.RunStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private val schedulerJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

internal class SchedulerRoomStore(
    database: SchedulerRoomDatabase,
    private val maxExecutionLogs: Int,
) {
    private val dao = database.schedulerDao()

    fun getAllSchedules(): List<ActionSpec> {
        val rows = runBlocking { dao.getAllSchedules() }
        return rows.mapNotNull { row ->
            runCatching {
                ActionSpec(
                    actionId = row.id,
                    actionType = row.actionType,
                    payloadJson = row.payloadJson,
                    recurrence = schedulerJson.decodeFromString<RecurrenceRule>(row.recurrenceRuleJson),
                    timezoneId = row.timezoneId,
                    enabled = row.enabled,
                    constraints = schedulerJson.decodeFromString<ActionConstraints>(row.constraintsJson)
                )
            }.getOrNull()
        }
    }

    fun upsertSchedule(spec: ActionSpec) {
        runBlocking {
            dao.upsertSchedule(
                ActionScheduleEntity(
                    id = spec.actionId,
                    actionType = spec.actionType,
                    payloadJson = spec.payloadJson,
                    recurrenceRuleJson = schedulerJson.encodeToString(spec.recurrence),
                    timezoneId = spec.timezoneId,
                    enabled = spec.enabled,
                    constraintsJson = schedulerJson.encodeToString(spec.constraints)
                )
            )
        }
    }

    fun deleteSchedule(actionId: String) {
        runBlocking { dao.deleteSchedule(actionId) }
    }

    fun upsertExecution(execution: ActionExecutionEntity) {
        runBlocking {
            dao.upsertExecution(execution)
            trimLogs(maxExecutionLogs.coerceAtLeast(20))
        }
    }

    fun getExecution(executionId: String): ActionExecutionEntity? {
        return runBlocking { dao.getExecution(executionId) }
    }

    fun getPendingExecutions(): List<ActionExecutionEntity> {
        return runBlocking { dao.getExecutionsByStatus(RunStatus.PENDING.name) }
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
                    dao.selectRecentExecutionsByScheduleAndStatuses(
                        scheduleId = actionId,
                        statuses = statuses.map { it.name },
                        limit = effectiveLimit,
                    )

                actionId != null ->
                    dao.selectRecentExecutionsBySchedule(
                        scheduleId = actionId,
                        limit = effectiveLimit,
                    )

                statuses.isNotEmpty() ->
                    dao.selectRecentExecutionsByStatuses(
                        statuses = statuses.map { it.name },
                        limit = effectiveLimit,
                    )

                else -> dao.selectRecentExecutions(limit = effectiveLimit)
            }
        }

        return rows.map { row ->
            val status = runCatching { RunStatus.valueOf(row.status) }.getOrDefault(RunStatus.FAILED)
            ExecutionLog(
                runId = row.id,
                actionId = row.scheduleId,
                triggerId = "trigger_${row.id}", // Backwards compatibility for now
                scheduledAtEpochMillis = row.scheduledAtEpochMillis,
                startedAtEpochMillis = row.startedAtEpochMillis ?: 0L,
                endedAtEpochMillis = row.endedAtEpochMillis ?: 0L,
                status = status,
                errorCode = row.errorCode,
                errorMessage = row.errorMessage,
                platformReason = null, // Deprecated in new model
            )
        }
    }

    private suspend fun trimLogs(maxEntries: Int) {
        dao.deleteOverflowExecutions(keep = maxEntries.toLong())
    }
}