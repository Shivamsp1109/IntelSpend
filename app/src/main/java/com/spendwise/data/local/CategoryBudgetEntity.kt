package com.spendwise.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.spendwise.domain.model.CategoryBudget
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory

/**
 * A monthly ceiling for one category.
 *
 * A standing limit rather than a per-month figure: someone who decides ₹8,000 of
 * eating out is enough means that every month, and asking them to set it again
 * each January is how a budget feature stops being used by February. It is
 * measured against whatever the current calendar month has spent so far, and
 * resets when the month does.
 *
 * Unique on category and currency together, so a rupee budget is never silently
 * measured against dollar spending and two limits for one category cannot
 * coexist with no rule for which wins.
 */
@Entity(
    tableName = "category_budgets",
    indices = [Index(value = ["category", "currency"], unique = true)]
)
data class CategoryBudgetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    /** [ExpenseCategory] label, e.g. "Food & Dining". */
    val category: String,
    val monthlyLimit: Double,
    val currency: String = Currency.INR.code,
    /**
     * The highest percentage already announced this month, as a whole number.
     *
     * Kept so crossing 80% is mentioned once rather than on every scan, and so
     * passing 100% still gets its own word even though 80% was already said.
     */
    val lastAlertedThreshold: Int = 0,
    /**
     * The month those alerts belong to, as `yyyy-MM`.
     *
     * Stored rather than inferred from a timestamp because the reset it drives is
     * a calendar question, not an elapsed-time one: a budget starts afresh on the
     * 1st regardless of how many days have passed since the last alert.
     */
    val lastAlertedMonth: String? = null,
    val isSynced: Boolean = false
)

fun CategoryBudgetEntity.toDomain(): CategoryBudget = CategoryBudget(
    id = id,
    category = ExpenseCategory.fromLabel(category),
    monthlyLimit = monthlyLimit,
    currency = Currency.fromCode(currency)
)

fun CategoryBudget.toEntity(): CategoryBudgetEntity = CategoryBudgetEntity(
    id = id,
    category = category.label,
    monthlyLimit = monthlyLimit,
    currency = currency.code
)
