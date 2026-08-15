package com.spendwise.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** What one category has spent in a period, for measuring a budget against. */
data class CategorySpendRow(
    val category: String,
    val currency: String,
    val spent: Double
)

@Dao
interface CategoryBudgetDao {

    @Query("SELECT * FROM category_budgets ORDER BY category ASC")
    fun observeBudgets(): Flow<List<CategoryBudgetEntity>>

    @Query("SELECT * FROM category_budgets")
    suspend fun getBudgets(): List<CategoryBudgetEntity>

    @Query("SELECT * FROM category_budgets WHERE isSynced = 0")
    suspend fun getPendingSync(): List<CategoryBudgetEntity>

    @Query("SELECT * FROM category_budgets WHERE category = :category AND currency = :currency")
    suspend fun getBudgetFor(category: String, currency: String): CategoryBudgetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudget(budget: CategoryBudgetEntity): Long

    @Update
    suspend fun updateBudget(budget: CategoryBudgetEntity)

    @Delete
    suspend fun deleteBudget(budget: CategoryBudgetEntity)

    /**
     * What each category has spent in a window.
     *
     * Filtered to spending, like every other aggregate in this app. Money moved
     * between the user's own accounts, a card bill settling purchases already
     * counted, an EMI or an investment — none of those are consumption, and
     * letting any of them eat into a category budget would report someone as
     * overspent for shifting their own savings around.
     */
    @Query(
        """
        SELECT category AS category,
               currency AS currency,
               SUM(amount) AS spent
        FROM expenses
        WHERE date BETWEEN :start AND :end
          AND nature = 'Spending'
        GROUP BY category, currency
        """
    )
    fun observeCategorySpend(start: Long, end: Long): Flow<List<CategorySpendRow>>

    @Query(
        """
        SELECT category AS category,
               currency AS currency,
               SUM(amount) AS spent
        FROM expenses
        WHERE date BETWEEN :start AND :end
          AND nature = 'Spending'
        GROUP BY category, currency
        """
    )
    suspend fun getCategorySpend(start: Long, end: Long): List<CategorySpendRow>

    /** Records that an alert has gone out, so the next scan stays quiet. */
    @Query(
        """
        UPDATE category_budgets
        SET lastAlertedThreshold = :threshold, lastAlertedMonth = :month
        WHERE id = :id
        """
    )
    suspend fun markAlerted(id: Int, threshold: Int, month: String)
}
