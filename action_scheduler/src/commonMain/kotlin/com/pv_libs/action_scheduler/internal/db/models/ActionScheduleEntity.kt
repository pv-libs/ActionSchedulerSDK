package com.pv_libs.action_scheduler.internal.db.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ActionSchedule")
data class ActionScheduleEntity(
    @PrimaryKey val id: String,
    val actionType: String,
    val payloadJson: String,
    val recurrenceRuleJson: String,
    val timezoneId: String,
    val enabled: Boolean,
    val constraintsJson: String
)