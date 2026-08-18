package com.spendwise.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.Goal
import com.spendwise.domain.model.GoalAmountBasis
import com.spendwise.domain.model.GoalFlexibility
import com.spendwise.domain.model.GoalPriority
import com.spendwise.domain.model.GoalStatusLifecycle
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
    /** The figure the user committed to. Never overwritten by a suggestion. */
    val monthlyContribution: Double = 0.0,
    val currency: String = Currency.INR.code,
    /**
     * Whether [targetAmount] is today's price or already a future figure.
     *
     * Defaults to today's money for rows that existed before this column, which
     * is the commoner reading — and the app asks the next time the goal is
     * edited rather than leaving the assumption invisible.
     */
    val amountBasis: String = GoalAmountBasis.TODAYS_MONEY.name,
    val priority: String = GoalPriority.IMPORTANT.name,
    val flexibility: String = GoalFlexibility.BOTH_FLEXIBLE.name,
    val lifecycle: String = GoalStatusLifecycle.ACTIVE.name,
    val fundingSource: String? = null,
    val isSynced: Boolean = false
)

fun GoalEntity.toDomain(): Goal = Goal(
    id = id,
    type = GoalType.fromName(type),
    targetAmount = targetAmount,
    targetDate = targetDate,
    currentSaved = currentSaved,
    monthlyContribution = monthlyContribution,
    currency = Currency.fromCode(currency),
    amountBasis = GoalAmountBasis.fromName(amountBasis),
    priority = GoalPriority.fromName(priority),
    flexibility = GoalFlexibility.fromName(flexibility),
    lifecycle = GoalStatusLifecycle.fromName(lifecycle),
    fundingSource = fundingSource,
    isSynced = isSynced
)

fun Goal.toEntity(): GoalEntity = GoalEntity(
    id = id,
    type = type.name,
    targetAmount = targetAmount,
    targetDate = targetDate,
    currentSaved = currentSaved,
    monthlyContribution = monthlyContribution,
    currency = currency.code,
    amountBasis = amountBasis.name,
    priority = priority.name,
    flexibility = flexibility.name,
    lifecycle = lifecycle.name,
    fundingSource = fundingSource,
    isSynced = isSynced
)
