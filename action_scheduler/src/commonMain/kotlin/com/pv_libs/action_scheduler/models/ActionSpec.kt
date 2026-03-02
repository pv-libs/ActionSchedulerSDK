package com.pv_libs.action_scheduler.models

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class ActionSpec(
    val actionId: String,
    val actionType: String,
    val payloadJson: String,
    val recurrence: RecurrenceRule,
    val timezoneId: String = TimeZone.currentSystemDefault().id,
    val notificationOffsetMinutes: Int? = null,
    val notificationTitle: String? = null,
    val notificationDescription: String? = null,
    val enabled: Boolean = true,
    val constraints: ActionConstraints = ActionConstraints(),
)

@Serializable
data class ActionConstraints(
    val requiresNetwork: Boolean = false,
    val isHeavyTask: Boolean = false,
    val backoffDelayMs: Long = 30_000,
)

@Serializable
sealed interface RecurrenceRule {
    @Serializable
    @SerialName("one_time")
    data class OneTime(val at: Instant) : RecurrenceRule

    @Serializable
    @SerialName("daily")
    data class Daily(val hour: Int, val minute: Int) : RecurrenceRule

    @Serializable
    @SerialName("weekly")
    data class Weekly(
        /** ISO day number: 1(Monday) .. 7(Sunday). */
        val dayOfWeekIso: Int,
        val hour: Int,
        val minute: Int,
    ) : RecurrenceRule

    @Serializable
    @SerialName("monthly")
    data class Monthly(
        /** 1..31, clamped to month length for short months. */
        val dayOfMonth: Int,
        val hour: Int,
        val minute: Int,
    ) : RecurrenceRule
}

