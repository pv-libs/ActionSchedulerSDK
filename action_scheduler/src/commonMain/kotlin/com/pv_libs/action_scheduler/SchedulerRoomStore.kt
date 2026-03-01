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
                    notificationOffsetMinutes = row.notificationOffsetMinutes,
                    notificationTitle = row.notificationTitle,
                    notificationDescription = row.notificationDescription,
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
                    notificationOffsetMinutes = row.notificationOffsetMinutes,
                    notificationTitle = row.notificationTitle,
                    notificationDescription = row.notificationDescription,
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
                notificationOffsetMinutes = spec.notificationOffsetMinutes,
                notificationTitle = spec.notificationTitle,
                notificationDescription = spec.notificationDescription,
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
                    scheduledAt = row.scheduledAt,
                    startedAt = row.startedAt,
                    endedAt = row.endedAt,
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

    private suspend fun trimLogs(maxEntries: Int) {
        dao.deleteOverflowExecutions(keep = maxEntries.toLong())
    }
}