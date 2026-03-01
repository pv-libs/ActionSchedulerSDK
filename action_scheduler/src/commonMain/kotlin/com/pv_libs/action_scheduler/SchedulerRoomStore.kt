package com.pv_libs.action_scheduler

import com.pv_libs.action_scheduler.internal.db.SchedulerRoomDatabase
import com.pv_libs.action_scheduler.internal.db.models.ActionExecutionEntity
import com.pv_libs.action_scheduler.internal.db.models.ActionScheduleEntity
import com.pv_libs.action_scheduler.models.ActionConstraints
import com.pv_libs.action_scheduler.models.ActionSpec
import com.pv_libs.action_scheduler.models.ExecutionLog
import com.pv_libs.action_scheduler.models.RecurrenceRule
import com.pv_libs.action_scheduler.models.RunStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.collections.map

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

    suspend fun getAllSchedules(): List<ActionSpec> {
        val rows = dao.getAllSchedules()
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

    fun getAllSchedulesFlow() = dao.getAllSchedulesFlow().map {
        it.mapNotNull { row ->
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

    suspend fun upsertSchedule(spec: ActionSpec) {
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

    suspend fun deleteSchedule(actionId: String) {
        dao.deleteSchedule(actionId)
    }

    suspend fun upsertExecution(execution: ActionExecutionEntity) {
        dao.upsertExecution(execution)
        trimLogs(maxExecutionLogs.coerceAtLeast(20))
    }

    fun getExecutionsListFlow(): Flow<List<ExecutionLog>> {
        return dao.getExecutionsListFlow().map { list ->
            list.map { row ->
                ExecutionLog(
                    runId = row.id,
                    actionId = row.scheduleId,
                    triggerId = "trigger_${row.id}", // Backwards compatibility for now
                    scheduledAtEpochMillis = row.scheduledAtEpochMillis,
                    startedAtEpochMillis = row.startedAtEpochMillis ?: 0L,
                    endedAtEpochMillis = row.endedAtEpochMillis ?: 0L,
                    status = row.status,
                    errorCode = row.errorCode,
                    errorMessage = row.errorMessage,
                    platformReason = null, // Deprecated in new model
                )
            }
        }
    }

    suspend fun getExecution(executionId: String): ActionExecutionEntity? {
        return dao.getExecution(executionId)
    }

    suspend fun getPendingExecutions(): List<ActionExecutionEntity> {
        return dao.getExecutionsByStatus(RunStatus.PENDING.name)
    }

    suspend fun getRecentLogs(
        actionId: String?,
        statuses: Set<RunStatus>,
        limit: Int,
    ): List<ExecutionLog> {
        val effectiveLimit = limit.coerceAtLeast(1).toLong()
        val rows =
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

        return rows.map { row ->
            ExecutionLog(
                runId = row.id,
                actionId = row.scheduleId,
                triggerId = "trigger_${row.id}", // Backwards compatibility for now
                scheduledAtEpochMillis = row.scheduledAtEpochMillis,
                startedAtEpochMillis = row.startedAtEpochMillis ?: 0L,
                endedAtEpochMillis = row.endedAtEpochMillis ?: 0L,
                status = row.status,
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