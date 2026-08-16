package com.spendwise.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.RecurringCadence
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringSource
import com.spendwise.domain.model.RecurringStatus
import com.spendwise.domain.model.RecurringType
import com.spendwise.domain.model.TransactionNature

// ─────────────────────────────────────────────────────────────────────────────
// RecurringEntity
// ─────────────────────────────────────────────────────────────────────────────

// Indexed on status because every screen and worker that reads commitments
// filters to the live ones — a paused membership must not generate a reminder or
// count towards what is owed this month.
@Entity(
    tableName = "recurring",
    indices = [Index("status")]
)
data class RecurringEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val amount: Double,
    /** [RecurringCadence] name, e.g. "MONTHLY". */
    val cadence: String,
    /** [RecurringType] name, e.g. "FIXED". */
    val type: String,
    val isSynced: Boolean = false,
    /**
     * ISO 4217 code. An amount without one is ambiguous, and detection must never
     * average a ₹ series together with a $ series.
     */
    val currency: String = Currency.INR.code,
    /**
     * [TransactionNature] name, carried over from the payments this was inferred
     * from. Decides whether this commitment is consumption, debt repayment or
     * asset building — see [RecurringEntry].
     */
    val nature: String = TransactionNature.Spending.name,
    /** [ExpenseCategory] label, e.g. "Subscriptions". */
    val category: String = ExpenseCategory.Other.label,
    /** [RecurringSource] name: whether the user entered this or the app found it. */
    val source: String = RecurringSource.MANUAL.name,
    val occurrenceCount: Int = 0,
    val confidence: Double = 1.0,
    /** [RecurringStatus] name: ACTIVE, PAUSED or ENDED. */
    val status: String = RecurringStatus.ACTIVE.name,
    val lastOccurrenceDate: Long? = null,
    val nextDueDate: Long? = null,
    /** See [RecurringEntry.dueDayOfMonth] for why this is stored rather than derived. */
    val dueDayOfMonth: Int? = null,
    /**
     * A new price seen on a payment, waiting for the user to accept or refuse it.
     *
     * Held rather than applied. The amount is a figure the user agreed to, and it
     * decides what the app says they owe each month — so a charge that disagrees
     * with it is a question to put to them, not a correction to make on their
     * behalf. Null when there is nothing to ask about.
     */
    val pendingAmount: Double? = null,
    /**
     * A price change the user was shown and chose not to take.
     *
     * Kept so the same question is not asked every month. Without it, refusing a
     * change would last exactly until the next payment arrived at that figure.
     */
    val declinedAmount: Double? = null
)

fun RecurringEntity.toDomain(): RecurringEntry = RecurringEntry(
    id = id,
    title = title,
    amount = amount,
    cadence = RecurringCadence.fromName(cadence),
    type = RecurringType.fromName(type),
    currency = Currency.fromCode(currency),
    nature = TransactionNature.fromName(nature),
    category = ExpenseCategory.fromLabel(category),
    source = RecurringSource.fromName(source),
    occurrenceCount = occurrenceCount,
    confidence = confidence,
    status = RecurringStatus.fromName(status),
    lastOccurrenceDate = lastOccurrenceDate,
    nextDueDate = nextDueDate,
    dueDayOfMonth = dueDayOfMonth,
    pendingAmount = pendingAmount,
    declinedAmount = declinedAmount
)

fun RecurringEntry.toEntity(): RecurringEntity = RecurringEntity(
    id = id,
    title = title,
    amount = amount,
    cadence = cadence.name,
    type = type.name,
    currency = currency.code,
    nature = nature.name,
    category = category.label,
    source = source.name,
    occurrenceCount = occurrenceCount,
    confidence = confidence,
    status = status.name,
    lastOccurrenceDate = lastOccurrenceDate,
    nextDueDate = nextDueDate,
    dueDayOfMonth = dueDayOfMonth,
    pendingAmount = pendingAmount,
    declinedAmount = declinedAmount
)

// ─────────────────────────────────────────────────────────────────────────────
// Dismissed detection candidates
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A repeating pattern the user has said is not a commitment.
 *
 * Kept in its own table rather than as a [RecurringEntity] with a "dismissed"
 * flag, because the two are not the same kind of thing. A recurring entry has a
 * title, an amount and a cadence the user stands behind; a dismissal is just a
 * signature to skip, and storing one as the other would mean inventing
 * placeholder values that no screen should ever show and no total should count.
 *
 * [lastSeenAmount] and [cadence] record what was rejected, so a dismissal can be
 * reconsidered rather than being permanent. Someone who dismisses an irregular
 * trickle of payments to a shop should still be told when that shop later starts
 * charging them the same amount every month — the pattern genuinely changed, and
 * a blanket "never mention this merchant again" would hide it.
 */
@Entity(tableName = "dismissed_recurring_candidates")
data class DismissedRecurringCandidateEntity(
    /** [com.spendwise.domain.model.RecurringCandidate.signature]. */
    @PrimaryKey
    val signature: String,
    val merchant: String,
    val currency: String,
    val nature: String,
    val category: String,
    val cadence: String,
    val lastSeenAmount: Double,
    val lastSeenOccurrenceDate: Long,
    val dismissedAt: Long
)

// ─────────────────────────────────────────────────────────────────────────────
// Join table — RecurringEntry ↔ Expense (many-to-many)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Cross-reference table linking a [RecurringEntity] to its historical
 * [ExpenseEntity] occurrences.
 *
 * Both foreign keys cascade deletes so orphaned rows are never left behind:
 *  - Delete a recurring entry → all its cross-refs are removed.
 *  - Delete an expense → its cross-ref row is removed.
 *
 * An index on [expenseId] is declared so Room can efficiently answer
 * "which recurring entry does this expense belong to?" queries without
 * a full table scan.
 */
@Entity(
    tableName = "recurring_expense_cross_ref",
    primaryKeys = ["recurringId", "expenseId"],
    foreignKeys = [
        ForeignKey(
            entity = RecurringEntity::class,
            parentColumns = ["id"],
            childColumns = ["recurringId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ExpenseEntity::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("expenseId")]
)
data class RecurringExpenseCrossRef(
    val recurringId: Int,
    val expenseId: Int
)

// ─────────────────────────────────────────────────────────────────────────────
// POJO for Room relation queries
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Room relation POJO that fetches a [RecurringEntity] together with all its
 * linked [ExpenseEntity] rows in a single query via the join table.
 */
data class RecurringWithExpenses(
    @Embedded val recurring: RecurringEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = RecurringExpenseCrossRef::class,
            parentColumn = "recurringId",
            entityColumn = "expenseId"
        )
    )
    val expenses: List<ExpenseEntity>
)
