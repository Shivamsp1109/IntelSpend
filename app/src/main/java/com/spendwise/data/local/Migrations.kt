package com.spendwise.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration from schema version 1 → 2.
 *
 * Changes:
 *  1. [expenses] — three new columns (merchant, currency, source).
 *     All default-safe so existing rows survive without data loss.
 *  2. [incomes] — new table mirroring the expense structure.
 *  3. [goals]   — new savings-goal table.
 *  4. [recurring] — new recurring-entry stub table.
 *  5. [recurring_expense_cross_ref] — many-to-many join table with
 *     CASCADE foreign keys and an index on expenseId.
 *
 * This is a strictly **additive** migration — no existing data is dropped
 * or altered. No destructive fallback is used.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {

        // ── 1. Enrich expenses table ──────────────────────────────────────────
        db.execSQL("ALTER TABLE `expenses` ADD COLUMN `merchant` TEXT")
        db.execSQL(
            "ALTER TABLE `expenses` ADD COLUMN `currency` TEXT NOT NULL DEFAULT 'INR'"
        )
        db.execSQL(
            "ALTER TABLE `expenses` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'MANUAL'"
        )

        // ── 2. Create incomes table ───────────────────────────────────────────
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `incomes` (
                `id`        INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title`     TEXT    NOT NULL,
                `amount`    REAL    NOT NULL,
                `currency`  TEXT    NOT NULL,
                `source`    TEXT    NOT NULL,
                `note`      TEXT,
                `date`      INTEGER NOT NULL,
                `isSynced`  INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // ── 3. Create goals table ─────────────────────────────────────────────
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `goals` (
                `id`                   INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `type`                 TEXT    NOT NULL,
                `targetAmount`         REAL    NOT NULL,
                `targetDate`           INTEGER NOT NULL,
                `currentSaved`         REAL    NOT NULL,
                `monthlyContribution`  REAL    NOT NULL,
                `isSynced`             INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        // ── 4. Create recurring table ─────────────────────────────────────────
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `recurring` (
                `id`       INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title`    TEXT    NOT NULL,
                `amount`   REAL    NOT NULL,
                `cadence`  TEXT    NOT NULL,
                `type`     TEXT    NOT NULL,
                `isSynced` INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        // ── 5. Create many-to-many join table ─────────────────────────────────
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `recurring_expense_cross_ref` (
                `recurringId` INTEGER NOT NULL,
                `expenseId`   INTEGER NOT NULL,
                PRIMARY KEY (`recurringId`, `expenseId`),
                FOREIGN KEY (`recurringId`)
                    REFERENCES `recurring`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY (`expenseId`)
                    REFERENCES `expenses`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )

        // Index on expenseId so "which recurring owns this expense?" is O(log n)
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
                `index_recurring_expense_cross_ref_expenseId`
            ON `recurring_expense_cross_ref` (`expenseId`)
            """.trimIndent()
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `expense_delete_sync_queue` (
                `localId`  INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `learned_categories` (
                `merchant` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`merchant`)
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `income_delete_sync_queue` (
                `localId`  INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_income_delete_sync_queue_localId`
            ON `income_delete_sync_queue` (`localId`)
            """.trimIndent()
        )
    }
}

/**
 * Room migration from schema version 5 → 6.
 *
 * Adds indices on `expenses.date` and `incomes.date`.
 *
 * Every analytics aggregate is scoped to a date range, and without an index
 * SQLite full-scans the table for each one — which is invisible on a hand-typed
 * expense list but not once statement imports push the row count into the
 * thousands. Purely additive: no columns or rows are touched.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_date` ON `expenses` (`date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_incomes_date` ON `incomes` (`date`)")
    }
}
