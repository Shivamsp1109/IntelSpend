package com.spendwise.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringEntryDao {

    // ── RecurringEntity CRUD ──────────────────────────────────────────────────

    @Query("SELECT * FROM recurring ORDER BY title ASC")
    fun observeRecurring(): Flow<List<RecurringEntity>>

    @Query("SELECT * FROM recurring WHERE id = :id")
    suspend fun getById(id: Int): RecurringEntity?

    @Query("SELECT * FROM recurring WHERE isSynced = 0")
    suspend fun getPendingSync(): List<RecurringEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecurring(entry: RecurringEntity): Long

    @Update
    suspend fun updateRecurring(entry: RecurringEntity)

    @Delete
    suspend fun deleteRecurring(entry: RecurringEntity)

    // ── Cross-ref management ──────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkExpense(crossRef: RecurringExpenseCrossRef)

    @Delete
    suspend fun unlinkExpense(crossRef: RecurringExpenseCrossRef)

    /** All cross-refs for a recurring entry — use to bulk-unlink before deletion. */
    @Query("SELECT * FROM recurring_expense_cross_ref WHERE recurringId = :recurringId")
    suspend fun getCrossRefsFor(recurringId: Int): List<RecurringExpenseCrossRef>

    // ── Relation queries ──────────────────────────────────────────────────────

    /** Returns every [RecurringEntity] with its linked expense rows. */
    @Transaction
    @Query("SELECT * FROM recurring ORDER BY title ASC")
    fun observeRecurringWithExpenses(): Flow<List<RecurringWithExpenses>>

    /** Returns a single [RecurringEntity] with its linked expense rows. */
    @Transaction
    @Query("SELECT * FROM recurring WHERE id = :id")
    suspend fun getRecurringWithExpenses(id: Int): RecurringWithExpenses?

    /**
     * Returns the recurring entry that an expense is linked to, if any.
     * Useful for "show parent subscription" in the expense detail screen.
     */
    @Query(
        """
        SELECT r.* FROM recurring r
        INNER JOIN recurring_expense_cross_ref ref ON r.id = ref.recurringId
        WHERE ref.expenseId = :expenseId
        LIMIT 1
        """
    )
    suspend fun getRecurringForExpense(expenseId: Int): RecurringEntity?

    /**
     * Live commitments only.
     *
     * Paused and ended entries are excluded here rather than at each call site,
     * so a new caller cannot forget and quietly start counting a cancelled
     * subscription towards what the user owes.
     */
    @Query("SELECT * FROM recurring WHERE status = 'ACTIVE' ORDER BY title ASC")
    fun observeActiveRecurring(): Flow<List<RecurringEntity>>

    @Query("SELECT * FROM recurring WHERE status = 'ACTIVE'")
    suspend fun getActiveRecurring(): List<RecurringEntity>

    /**
     * Live commitments falling due within a window, for the reminder worker.
     *
     * Ordered by date so the soonest is mentioned first when several land together.
     */
    @Query(
        """
        SELECT * FROM recurring
        WHERE status = 'ACTIVE'
          AND nextDueDate IS NOT NULL
          AND nextDueDate BETWEEN :from AND :until
        ORDER BY nextDueDate ASC
        """
    )
    suspend fun getDueBetween(from: Long, until: Long): List<RecurringEntity>

    @Query("UPDATE recurring SET status = :status, isSynced = 0 WHERE id = :id")
    suspend fun updateStatus(id: Int, status: String)

    /**
     * Records that a payment landed, and when the next one is now expected.
     *
     * One statement rather than a read-modify-write, so two imports finishing at
     * once cannot each read the old date and write conflicting projections.
     */
    @Query(
        """
        UPDATE recurring
        SET lastOccurrenceDate = :lastOccurrenceDate,
            nextDueDate = :nextDueDate,
            occurrenceCount = occurrenceCount + :additionalOccurrences,
            isSynced = 0
        WHERE id = :id
        """
    )
    suspend fun recordOccurrence(
        id: Int,
        lastOccurrenceDate: Long,
        nextDueDate: Long,
        additionalOccurrences: Int
    )

    /** Commitments with a price change waiting for an answer. */
    @Query("SELECT * FROM recurring WHERE status = 'ACTIVE' AND pendingAmount IS NOT NULL")
    suspend fun getWithPendingPriceChange(): List<RecurringEntity>

    @Query("UPDATE recurring SET pendingAmount = :amount WHERE id = :id")
    suspend fun setPendingAmount(id: Int, amount: Double?)

    /**
     * Takes the new price. The commitment's amount becomes it, and any earlier
     * refusal is cleared — the user has now agreed to this figure.
     */
    @Query(
        """
        UPDATE recurring
        SET amount = :amount, pendingAmount = NULL, declinedAmount = NULL, isSynced = 0
        WHERE id = :id
        """
    )
    suspend fun applyPendingAmount(id: Int, amount: Double)

    /**
     * Keeps the agreed amount and remembers the refusal, so the same change is
     * not put to the user again every time another payment arrives at it.
     */
    @Query("UPDATE recurring SET pendingAmount = NULL, declinedAmount = :amount WHERE id = :id")
    suspend fun declinePendingAmount(id: Int, amount: Double)

    // ── Dismissed detection candidates ────────────────────────────────────────

    @Query("SELECT * FROM dismissed_recurring_candidates")
    suspend fun getDismissedCandidates(): List<DismissedRecurringCandidateEntity>

    @Query("SELECT * FROM dismissed_recurring_candidates")
    fun observeDismissedCandidates(): Flow<List<DismissedRecurringCandidateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDismissedCandidate(candidate: DismissedRecurringCandidateEntity)

    /** Used when a dismissal is reconsidered, so the candidate can surface again. */
    @Query("DELETE FROM dismissed_recurring_candidates WHERE signature = :signature")
    suspend fun deleteDismissedCandidate(signature: String)
}
