package com.spendwise.data.local

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Aggregate queries for the analytics screens.
 *
 * These deliberately return small projections rather than rows. The previous
 * approach loaded every expense ever recorded and summed in Kotlin on each
 * emission, which is fine for a hand-typed list and not fine once statement
 * imports push the table into the thousands. `GROUP BY` over the indexed `date`
 * column keeps the work in SQLite and the result proportional to the number of
 * buckets on screen, not the size of the history.
 *
 * Every query is scoped by date range *and* currency. Summing ₹ and $ into one
 * figure would be silently wrong, so the caller always states which currency it
 * is asking about — see AnalyticsRepository.primaryCurrency.
 *
 * Date bucketing uses SQLite's `strftime` on `date / 1000` (millis → epoch
 * seconds), with 'localtime' so buckets line up with the user's calendar rather
 * than UTC. Without it, an evening transaction lands in the following day for
 * anyone east of Greenwich.
 *
 * Every expense aggregate also filters `nature = 'Spending'`. A statement import
 * picks up transfers between the user's own accounts, credit-card bill payments,
 * EMI and ATM withdrawals as debits alongside real purchases. Counted, a single
 * transfer between two of someone's own accounts makes the month look ruinous
 * and every chart on the screen lies at once. Duplicate detection deliberately
 * does *not* filter this way — a repeated transfer is still a repeat.
 */
@Dao
interface AnalyticsDao {

    // ── Totals ────────────────────────────────────────────────────────────────

    @Query(
        """
        SELECT COALESCE(SUM(amount), 0) FROM expenses
        WHERE date BETWEEN :start AND :end AND currency = :currency
          AND nature = 'Spending'
        """
    )
    suspend fun totalExpense(start: Long, end: Long, currency: String): Double

    @Query(
        """
        SELECT COALESCE(SUM(amount), 0) FROM incomes
        WHERE date BETWEEN :start AND :end AND currency = :currency
        """
    )
    suspend fun totalIncome(start: Long, end: Long, currency: String): Double

    @Query(
        """
        SELECT COUNT(*) FROM expenses
        WHERE date BETWEEN :start AND :end AND currency = :currency
          AND nature = 'Spending'
        """
    )
    suspend fun expenseCount(start: Long, end: Long, currency: String): Int

    // ── Time buckets ──────────────────────────────────────────────────────────

    @Query(
        """
        SELECT strftime('%Y-%m-%d', date / 1000, 'unixepoch', 'localtime') AS bucket,
               SUM(amount) AS total
        FROM expenses
        WHERE date BETWEEN :start AND :end AND currency = :currency
          AND nature = 'Spending'
        GROUP BY bucket
        ORDER BY bucket ASC
        """
    )
    suspend fun expenseByDay(start: Long, end: Long, currency: String): List<BucketTotal>

    @Query(
        """
        SELECT strftime('%Y-%m', date / 1000, 'unixepoch', 'localtime') AS bucket,
               SUM(amount) AS total
        FROM expenses
        WHERE date BETWEEN :start AND :end AND currency = :currency
          AND nature = 'Spending'
        GROUP BY bucket
        ORDER BY bucket ASC
        """
    )
    suspend fun expenseByMonth(start: Long, end: Long, currency: String): List<BucketTotal>

    @Query(
        """
        SELECT strftime('%Y-%m', date / 1000, 'unixepoch', 'localtime') AS bucket,
               SUM(amount) AS total
        FROM incomes
        WHERE date BETWEEN :start AND :end AND currency = :currency
        GROUP BY bucket
        ORDER BY bucket ASC
        """
    )
    suspend fun incomeByMonth(start: Long, end: Long, currency: String): List<BucketTotal>

    @Query(
        """
        SELECT strftime('%Y-%m-%d', date / 1000, 'unixepoch', 'localtime') AS bucket,
               SUM(amount) AS total
        FROM incomes
        WHERE date BETWEEN :start AND :end AND currency = :currency
        GROUP BY bucket
        ORDER BY bucket ASC
        """
    )
    suspend fun incomeByDay(start: Long, end: Long, currency: String): List<BucketTotal>

    /** '0' = Sunday through '6' = Saturday, per SQLite's %w. */
    @Query(
        """
        SELECT strftime('%w', date / 1000, 'unixepoch', 'localtime') AS bucket,
               SUM(amount) AS total
        FROM expenses
        WHERE date BETWEEN :start AND :end AND currency = :currency
          AND nature = 'Spending'
        GROUP BY bucket
        ORDER BY bucket ASC
        """
    )
    suspend fun expenseByWeekday(start: Long, end: Long, currency: String): List<BucketTotal>

    // ── Breakdowns ────────────────────────────────────────────────────────────

    @Query(
        """
        SELECT category AS label, SUM(amount) AS total, COUNT(*) AS count
        FROM expenses
        WHERE date BETWEEN :start AND :end AND currency = :currency
          AND nature = 'Spending'
        GROUP BY category
        ORDER BY total DESC
        """
    )
    suspend fun expenseByCategory(start: Long, end: Long, currency: String): List<LabelTotal>

    /**
     * Rows with no merchant are excluded rather than bucketed under a blank
     * label — a "(none)" row at the top of a top-merchants chart is noise.
     */
    @Query(
        """
        SELECT merchant AS label, SUM(amount) AS total, COUNT(*) AS count
        FROM expenses
        WHERE date BETWEEN :start AND :end AND currency = :currency
          AND nature = 'Spending'
          AND merchant IS NOT NULL AND merchant != ''
        GROUP BY merchant
        ORDER BY total DESC
        LIMIT :limit
        """
    )
    suspend fun topMerchants(
        start: Long,
        end: Long,
        currency: String,
        limit: Int
    ): List<LabelTotal>

    @Query(
        """
        SELECT id, title, amount, category, date, merchant
        FROM expenses
        WHERE date BETWEEN :start AND :end AND currency = :currency
          AND nature = 'Spending'
        ORDER BY amount DESC
        LIMIT :limit
        """
    )
    suspend fun largestExpenses(
        start: Long,
        end: Long,
        currency: String,
        limit: Int
    ): List<LargeExpense>

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * The rows behind the aggregates, scoped identically, so an exported report
     * adds up to exactly what the screen reported.
     */
    @Query(
        """
        SELECT * FROM expenses
        WHERE date BETWEEN :start AND :end AND currency = :currency
          AND nature = 'Spending'
        ORDER BY date DESC
        """
    )
    suspend fun expensesInRange(start: Long, end: Long, currency: String): List<ExpenseEntity>

    // ── Currency ──────────────────────────────────────────────────────────────

    /**
     * Currencies present in the expense table, most-used first. Drives the
     * primary-currency default so analytics never silently mixes denominations.
     */
    @Query(
        """
        SELECT currency AS label, SUM(amount) AS total, COUNT(*) AS count
        FROM expenses
        WHERE nature = 'Spending'
        GROUP BY currency
        ORDER BY count DESC
        """
    )
    suspend fun currencyUsage(): List<LabelTotal>

    // ── Change signals ────────────────────────────────────────────────────────

    /**
     * Re-emits whenever the table is written to, so the analytics screen can
     * reload after an expense is added, edited, or imported.
     *
     * The aggregates above are one-shot `suspend` reads by design — twelve
     * separate Flows would each fire on every write and the screen would
     * assemble itself in pieces. One signal, one reload.
     *
     * The count itself is ignored. Room invalidates per table, not per value,
     * and does not de-duplicate Flow emissions, so editing an amount without
     * changing the row count still triggers this.
     */
    @Query("SELECT COUNT(*) FROM expenses")
    fun expenseChanges(): Flow<Int>

    @Query("SELECT COUNT(*) FROM incomes")
    fun incomeChanges(): Flow<Int>
}

/** A time bucket key ('2026-08-11', '2026-08', or a weekday index) and its total. */
data class BucketTotal(
    val bucket: String,
    val total: Double
)

data class LabelTotal(
    val label: String,
    val total: Double,
    val count: Int
)

data class LargeExpense(
    val id: Int,
    val title: String,
    val amount: Double,
    val category: String,
    val date: Long,
    val merchant: String?
)
