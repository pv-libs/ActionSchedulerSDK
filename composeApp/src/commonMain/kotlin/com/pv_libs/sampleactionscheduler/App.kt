package com.pv_libs.sampleactionscheduler

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pv_libs.action_scheduler.ActionSchedulerKit
import com.pv_libs.action_scheduler.models.ExecutionLog
import com.pv_libs.action_scheduler.models.RegistrationResult
import kotlinx.coroutines.launch


@Composable
@Preview
fun App() {
    MaterialTheme {
        val scope = rememberCoroutineScope()
        var statusMessage by remember { mutableStateOf("Idle") }
        var executionLogs by remember { mutableStateOf<List<ExecutionLog>>(emptyList()) }

        val scheduler = ActionSchedulerKit.getOrNull()

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
                text = "${Greeting().greet()}\nStatus: $statusMessage",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (scheduler == null) {
                Text(
                    text = "Scheduler runtime is not initialized. Initialize ActionSchedulerKit from platform startup.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val result = scheduleDailySampleAction(scheduler)
                        statusMessage = result.toStatusLabel("Daily")
                        executionLogs = scheduler.getRecentExecutions(limit = 20)
                    }
                }) {
                    Text("Schedule Daily")
                }
                Button(onClick = {
                    scope.launch {
                        val result = scheduleMonthlySampleAction(scheduler)
                        statusMessage = result.toStatusLabel("Monthly")
                        executionLogs = scheduler.getRecentExecutions(limit = 20)
                    }
                }) {
                    Text("Schedule Monthly")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        cancelDailySampleAction(scheduler)
                        statusMessage = "Daily action cancelled"
                        executionLogs = scheduler.getRecentExecutions(limit = 20)
                    }
                }) {
                    Text("Cancel Daily")
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        cancelMonthlySampleAction(scheduler)
                        statusMessage = "Monthly action cancelled"
                        executionLogs = scheduler.getRecentExecutions(limit = 20)
                    }
                }) {
                    Text("Cancel Monthly")
                }
            }

            OutlinedButton(onClick = {
                scope.launch {
                    executionLogs = scheduler.getRecentExecutions(limit = 20)
                    statusMessage = "Logs refreshed"
                }
            }) {
                Text("Refresh Logs")
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text("Recent Executions", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(executionLogs) { log ->
                    Text(
                        text = "${log.actionId} • ${log.status} • ${log.errorCode ?: "OK"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun RegistrationResult.toStatusLabel(actionName: String): String {
    return "$actionName action: $this"
}