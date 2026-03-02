package com.pv_libs.sampleactionscheduler

const val ACTION_TYPE_DIGI_GOLD = "DIGI_GOLD"
const val ACTION_TYPE_CUSTOM_REMINDER = "CUSTOM_REMINDER"

enum class ReminderRecurrence {
    DAILY,
    WEEKLY,
    BI_WEEKLY,
    MONTHLY,
    ONE_TIME,
}

data class CustomReminderInput(
    val actionName: String,
    val recurrence: ReminderRecurrence,
    val notificationOffsetMinutes: Int?,
    val hour: Int,
    val minute: Int,
    val dayOfWeekIso: Int = 1,
    val dayOfMonth: Int = 1,
    val oneTimeYear: Int = 2026,
    val oneTimeMonth: Int = 1,
    val oneTimeDay: Int = 1,
)
