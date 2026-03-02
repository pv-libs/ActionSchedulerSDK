package com.pv_libs.sampleactionscheduler

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pv_libs.action_scheduler.ActionScheduler
import com.pv_libs.action_scheduler.ActionSchedulerKit
import com.pv_libs.action_scheduler.models.RegistrationResult
import com.pv_libs.action_scheduler.models.RunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    MaterialTheme {
        val scope = rememberCoroutineScope()
        var statusMessage by remember { mutableStateOf("Idle") }
        var showCreateActionSheet by remember { mutableStateOf(false) }
        val permissionHandler = rememberNotificationPermissionHandler()

        val scheduler = remember {
            ActionSchedulerKit.getOrNull()
        }
        val registeredActions = remember(scheduler) {
            scheduler?.getRegisteredActions() ?: emptyFlow()
        }.collectAsState(emptyList())
        val executionLogs = remember(scheduler) {
            scheduler?.getExecutionLogs() ?: emptyFlow()
        }.collectAsState(emptyList())

        Scaffold {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .safeContentPadding()
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Action Scheduler Sample",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "Status: $statusMessage",
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (scheduler == null) {
                    Text(
                        text = "Scheduler runtime is not initialized. Initialize ActionSchedulerKit from platform startup.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    return@Column
                }

                Button(onClick = { showCreateActionSheet = true }) {
                    Text("Create / Manage Reminder")
                }


                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    stickyHeader {
                        Text(
                            text = "Registered Actions",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(8.dp)
                        )
                    }
                    items(registeredActions.value) { log ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "actionId: ${log.actionId}\n\n$log",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(16.dp)
                                    .weight(1f)
                            )
                            Button(onClick = {
                                scope.launch {
                                    cancelCustomAction(scheduler, log.actionId)
                                    statusMessage = "Cancelled action: ${log.actionId}"
                                }
                            }) {
                                Text("delete")
                            }
                        }

                    }
                    stickyHeader {
                        Text(
                            text = "Execution Logs",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(8.dp)
                                .padding(top = 8.dp)
                        )
                    }
                    items(executionLogs.value) { log ->
                        val color = when (log.status) {
                            RunStatus.FAILED -> Color.Red
                            RunStatus.SUCCESS -> Color.Green
                            RunStatus.RUNNING -> Color.Blue
                            RunStatus.PENDING -> Color.Yellow
                            else -> Color.Gray
                        }.copy(alpha = 0.2f)
                        Card(
                            colors = CardDefaults.cardColors().copy(containerColor = color),
                            modifier = Modifier.padding(8.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = "scheduledAt - ${log.scheduledAt.toLocalDateTime(TimeZone.currentSystemDefault())}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                                Text(
                                    text = "RunId - ${log.runId}\nStatus - ${log.status}\nActionId - ${log.actionId}\n\n$log",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                if (showCreateActionSheet) {
                    val createActionState = remember { CreateActionUIState() }
                    val bottomSheetState = rememberModalBottomSheetState(true)
                    ModalBottomSheet(
                        sheetState = bottomSheetState,
                        onDismissRequest = { showCreateActionSheet = false },
                    ) {
                        CreateActionUI(
                            state = createActionState,
                            onCreateClick = {
                                createAction(
                                    createActionState = createActionState,
                                    permissionHandler = permissionHandler,
                                    scheduler = scheduler,
                                    scope = scope,
                                    updateState = {
                                        statusMessage = it
                                    }
                                )
                                scope.launch {
                                    bottomSheetState.hide()
                                }
                            },
                            onCancelClick = {
                                val trimmedName = createActionState.actionName.trim()
                                if (trimmedName.isBlank()) {
                                    statusMessage = "Enter action name to cancel"
                                    return@CreateActionUI
                                }
                                scope.launch {
                                    cancelCustomAction(scheduler, trimmedName)
                                    statusMessage = "Cancelled action: $trimmedName"
                                    showCreateActionSheet = false
                                }
                            },
                        )
                    }
                }
            }
        }

    }
}

private fun createAction(
    createActionState: CreateActionUIState,
    permissionHandler: NotificationPermissionHandler,
    scheduler: ActionScheduler,
    scope: CoroutineScope,
    updateState: (String) -> Unit
) {
    val trimmedName = createActionState.actionName.trim()
    if (trimmedName.isBlank()) {
        updateState("Action name is required")
        return
    }

    val hour = createActionState.hourInput.toIntOrNull()
    val minute = createActionState.minuteInput.toIntOrNull()
    if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
        updateState("Enter valid hour/minute")
        return
    }

    val dayOfWeekIso = createActionState.dayOfWeekInput.toIntOrNull() ?: 1
    val dayOfMonth = createActionState.dayOfMonthInput.toIntOrNull() ?: 1
    val oneTimeYear = createActionState.oneTimeYearInput.toIntOrNull() ?: 2026
    val oneTimeMonth = createActionState.oneTimeMonthInput.toIntOrNull() ?: 1
    val oneTimeDay = createActionState.oneTimeDayInput.toIntOrNull() ?: 1
    val notificationOffsetMinutes = createActionState.reminderOffsetInput
        .trim()
        .takeIf { it.isNotEmpty() }
        ?.toIntOrNull()

    permissionHandler.checkAndRequestPermission { isGranted ->
        if (isGranted) {
            scope.launch {
                val result = scheduleCustomAction(
                    scheduler = scheduler,
                    input = CustomReminderInput(
                        actionName = trimmedName,
                        recurrence = createActionState.recurrence,
                        notificationOffsetMinutes = notificationOffsetMinutes,
                        hour = hour,
                        minute = minute,
                        dayOfWeekIso = dayOfWeekIso,
                        dayOfMonth = dayOfMonth,
                        oneTimeYear = oneTimeYear,
                        oneTimeMonth = oneTimeMonth,
                        oneTimeDay = oneTimeDay,
                    )
                )
                updateState(result.toStatusLabel(trimmedName))
            }
        } else {
            updateState("Notification permission denied")
        }
    }
}

private fun RegistrationResult.toStatusLabel(actionName: String): String {
    return "$actionName action: $this"
}