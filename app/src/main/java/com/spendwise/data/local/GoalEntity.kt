package com.spendwise.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.spendwise.domain.model.Goal
import com.spendwise.domain.model.GoalType

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    /** Stored as enum name, e.g. "EMERGENCY_FUND". */
    val type: String,
    val targetAmount: Double,
    /** Epoch-millis deadline. */
    val targetDate: Long,
    val currentSaved: Double = 0.0,
    val monthlyContribution: Double = 0.0,
    val isSynced: Boolean = false
)

fun GoalEntity.toDomain(): Goal = Goal(
    id = id,
    type = GoalType.fromName(type),
    targetAmount = targetAmount,
    targetDate = targetDate,
    currentSaved = currentSaved,
    monthlyContribution = monthlyContribution,
    isSynced = isSynced
)

fun Goal.toEntity(): GoalEntity = GoalEntity(
    id = id,
    type = type.name,
    targetAmount = targetAmount,
    targetDate = targetDate,
    currentSaved = currentSaved,
    monthlyContribution = monthlyContribution,
    isSynced = isSynced
)
