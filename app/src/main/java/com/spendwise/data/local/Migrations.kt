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
