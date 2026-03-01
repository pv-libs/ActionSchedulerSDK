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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.pv_libs.action_scheduler.models.ActionSpec
import com.pv_libs.action_scheduler.models.ExecutionLog
import com.pv_libs.action_scheduler.models.RegistrationResult
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch


@Composable
fun App() {
    MaterialTheme {
        val scope = rememberCoroutineScope()
        var statusMessage by remember { mutableStateOf("Idle") }


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
                        }
                    }) {
                        Text("Schedule Daily")
                    }
                    Button(onClick = {
                        scope.launch {
                            val result = scheduleMonthlySampleAction(scheduler)
                            statusMessage = result.toStatusLabel("Monthly")
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
                        }
                    }) {
                        Text("Cancel Daily")
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            cancelMonthlySampleAction(scheduler)
                            statusMessage = "Monthly action cancelled"
                        }
                    }) {
                        Text("Cancel Monthly")
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

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
                        Text(
                            text = log.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp)
                        )
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
                        Text(
                            text = log.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }

    }
}

private fun RegistrationResult.toStatusLabel(actionName: String): String {
    return "$actionName action: $this"
}