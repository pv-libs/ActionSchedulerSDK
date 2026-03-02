package com.pv_libs.sampleactionscheduler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

@Stable
class CreateActionUIState {
    private val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    var actionName by mutableStateOf("")
    var reminderOffsetInput by mutableStateOf("")
    var recurrence by mutableStateOf(ReminderRecurrence.MONTHLY)
    var hourInput by mutableStateOf(now.hour.toString())
    var minuteInput by mutableStateOf(now.minute.toString())
    var dayOfWeekInput by mutableStateOf(now.date.dayOfWeek.isoDayNumber.toString())
    var dayOfMonthInput by mutableStateOf(now.day.toString())
    var oneTimeYearInput by mutableStateOf(now.year.toString())
    var oneTimeMonthInput by mutableStateOf(now.month.number.toString())
    var oneTimeDayInput by mutableStateOf(now.day.toString())
}

@Composable
fun CreateActionUI(
    state: CreateActionUIState,
    onCreateClick: () -> Unit,
    onCancelClick: () -> Unit,
) {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Action name is the unique identifier",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = state.actionName,
            onValueChange = { state.actionName = it },
            label = { Text("Action name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Text(
            text = "Recurrence",
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReminderRecurrence.entries.forEach { option ->
                if (state.recurrence == option) {
                    Button(
                        onClick = { state.recurrence = option },
                    ) {
                        Text(option.label())
                    }
                } else {
                    OutlinedButton(
                        onClick = { state.recurrence = option },
                    ) {
                        Text(option.label())
                    }
                }
            }
        }

        when (state.recurrence) {
            ReminderRecurrence.WEEKLY,
            ReminderRecurrence.BI_WEEKLY,
            -> {
                TimeSelector(
                    hour = state.hourInput.toIntOrNull() ?: 0,
                    minute = state.minuteInput.toIntOrNull() ?: 0,
                    onTimeChange = { hour, minute ->
                        state.hourInput = hour.toString()
                        state.minuteInput = minute.toString()
                    },
                )
                WeekDaySelector(
                    selectedIsoDay = state.dayOfWeekInput.toIntOrNull() ?: 1,
                    onDaySelected = { state.dayOfWeekInput = it.toString() },
                )
            }

            ReminderRecurrence.MONTHLY -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeSelector(
                        hour = state.hourInput.toIntOrNull() ?: 0,
                        minute = state.minuteInput.toIntOrNull() ?: 0,
                        onTimeChange = { hour, minute ->
                            state.hourInput = hour.toString()
                            state.minuteInput = minute.toString()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    DateSelector(
                        label = "Monthly run date",
                        year = state.oneTimeYearInput.toIntOrNull() ?: now.year,
                        month = state.oneTimeMonthInput.toIntOrNull() ?: now.month.number,
                        day = state.dayOfMonthInput.toIntOrNull() ?: now.day,
                        onDateChange = { year, month, day ->
                            state.oneTimeYearInput = year.toString()
                            state.oneTimeMonthInput = month.toString()
                            state.dayOfMonthInput = day.toString()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            ReminderRecurrence.ONE_TIME -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeSelector(
                        hour = state.hourInput.toIntOrNull() ?: 0,
                        minute = state.minuteInput.toIntOrNull() ?: 0,
                        onTimeChange = { hour, minute ->
                            state.hourInput = hour.toString()
                            state.minuteInput = minute.toString()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    DateSelector(
                        label = "One-time date",
                        year = state.oneTimeYearInput.toIntOrNull() ?: now.year,
                        month = state.oneTimeMonthInput.toIntOrNull() ?: now.month.number,
                        day = state.oneTimeDayInput.toIntOrNull() ?: now.day,
                        onDateChange = { year, month, day ->
                            state.oneTimeYearInput = year.toString()
                            state.oneTimeMonthInput = month.toString()
                            state.oneTimeDayInput = day.toString()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            ReminderRecurrence.DAILY -> {
                TimeSelector(
                    hour = state.hourInput.toIntOrNull() ?: 0,
                    minute = state.minuteInput.toIntOrNull() ?: 0,
                    onTimeChange = { hour, minute ->
                        state.hourInput = hour.toString()
                        state.minuteInput = minute.toString()
                    },
                )
            }
        }

        OutlinedTextField(
            value = state.reminderOffsetInput,
            onValueChange = { state.reminderOffsetInput = it },
            label = { Text("Reminder minutes before (optional)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCreateClick) {
                Text("Create Action")
            }
            OutlinedButton(onClick = onCancelClick) {
                Text("Cancel Action")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSelector(
    hour: Int,
    minute: Int,
    onTimeChange: (hour: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showPicker = remember { mutableStateOf(false) }
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = "Time", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { showPicker.value = true }, modifier = Modifier.fillMaxWidth()) {
            Text("${hour.coerceIn(0, 23).toString().padStart(2, '0')}:${minute.coerceIn(0, 59).toString().padStart(2, '0')}")
        }
    }

    if (showPicker.value) {
        val timePickerState = rememberTimePickerState(
            initialHour = hour.coerceIn(0, 23),
            initialMinute = minute.coerceIn(0, 59),
            is24Hour = false,
        )
        AlertDialog(
            onDismissRequest = { showPicker.value = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedHour = timePickerState.hour
                        val selectedMinute = timePickerState.minute
                        val isBeforeNow = selectedHour < now.hour ||
                            (selectedHour == now.hour && selectedMinute < now.minute)
                        if (isBeforeNow) {
                            onTimeChange(now.hour, now.minute)
                        } else {
                            onTimeChange(selectedHour, selectedMinute)
                        }
                        showPicker.value = false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker.value = false }) {
                    Text("Cancel")
                }
            },
            text = {
                TimePicker(state = timePickerState)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSelector(
    label: String,
    year: Int,
    month: Int,
    day: Int,
    onDateChange: (year: Int, month: Int, day: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showPicker = remember { mutableStateOf(false) }
    val timeZone = TimeZone.currentSystemDefault()
    val safeDate = runCatching {
        LocalDate(year.coerceIn(1970, 2100), month.coerceIn(1, 12), day.coerceIn(1, 31))
    }.getOrElse {
        Clock.System.now().toLocalDateTime(timeZone).date
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { showPicker.value = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                "${safeDate.year}-${safeDate.month.number.toString().padStart(2, '0')}-${safeDate.day.toString().padStart(2, '0')}"
            )
        }
    }

    if (showPicker.value) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = safeDate.atStartOfDayIn(timeZone).toEpochMilliseconds(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker.value = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedDate = datePickerState.selectedDateMillis
                            ?.let { millis ->
                                Instant.fromEpochMilliseconds(millis)
                                    .toLocalDateTime(timeZone)
                                    .date
                            }
                        if (selectedDate != null) {
                            onDateChange(
                                selectedDate.year,
                                selectedDate.month.number,
                                selectedDate.day,
                            )
                        }
                        showPicker.value = false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker.value = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun WeekDaySelector(
    selectedIsoDay: Int,
    onDaySelected: (Int) -> Unit,
) {
    Text(text = "Weekday", style = MaterialTheme.typography.titleSmall)
    val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val dayIso = index + 1
            if (dayIso == selectedIsoDay) {
                Button(onClick = { onDaySelected(dayIso) }) {
                    Text(label)
                }
            } else {
                OutlinedButton(onClick = { onDaySelected(dayIso) }) {
                    Text(label)
                }
            }
        }
    }
}

private fun ReminderRecurrence.label(): String {
    return when (this) {
        ReminderRecurrence.DAILY -> "Daily"
        ReminderRecurrence.WEEKLY -> "Weekly"
        ReminderRecurrence.BI_WEEKLY -> "Bi-weekly"
        ReminderRecurrence.MONTHLY -> "Monthly"
        ReminderRecurrence.ONE_TIME -> "One-time"
    }
}
