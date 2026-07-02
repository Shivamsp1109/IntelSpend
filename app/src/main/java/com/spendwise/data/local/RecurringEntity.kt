package com.spendwise.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.spendwise.domain.model.RecurringCadence
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringType

// ─────────────────────────────────────────────────────────────────────────────
// RecurringEntity
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "recurring")
data class RecurringEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val amount: Double,
    /** [RecurringCadence] name, e.g. "MONTHLY". */
    val cadence: String,
    /** [RecurringType] name, e.g. "FIXED". */
    val type: String,
    val isSynced: Boolean = false
)

fun RecurringEntity.toDomain(): RecurringEntry = RecurringEntry(
    id = id,
    title = title,
    amount = amount,
    cadence = RecurringCadence.fromName(cadence),
    type = RecurringType.fromName(type)
)

fun RecurringEntry.toEntity(): RecurringEntity = RecurringEntity(
    id = id,
    title = title,
    amount = amount,
    cadence = cadence.name,
    type = type.name
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
