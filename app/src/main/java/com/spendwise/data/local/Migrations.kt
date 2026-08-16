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

/**
 * Room migration from schema version 6 → 7.
 *
 * Adds a nullable `reference` column to both transaction tables, holding the
 * bank or UPI reference (RRN/UTR) when the imported document showed one, and
 * indexes it.
 *
 * This is what makes cross-document duplicate detection possible: a UPI
 * screenshot and the bank statement that lists the same payment weeks later
 * agree on almost nothing — the merchant name is written differently and the
 * timestamps differ — except this number. Every import checks it, so it is
 * indexed rather than scanned.
 *
 * Existing rows get NULL, which simply means the reference was never captured;
 * matching falls back to amount, date and merchant for those.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `expenses` ADD COLUMN `reference` TEXT")
        db.execSQL("ALTER TABLE `incomes` ADD COLUMN `reference` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_reference` ON `expenses` (`reference`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_incomes_reference` ON `incomes` (`reference`)")
    }
}

/**
 * Room migration from schema version 7 → 8.
 *
 * Records whether a transaction's date was read off the document or substituted
 * because none could be found.
 *
 * The distinction cannot be recovered after the fact — an assumed date looks
 * exactly like a real one — and duplicate detection needs it: a date that was
 * guessed must not be allowed to rule out a match, or a receipt scanned a week
 * late never lines up with the statement that later lists the same payment.
 *
 * Indexed because matching queries for exactly these rows, and they are rare,
 * which is when an index on a boolean is worth having rather than wasteful.
 *
 * Existing rows get 0: dates already stored were either read from a document or
 * typed by the user, and both are real.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `expenses` ADD COLUMN `dateIsAssumed` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `incomes` ADD COLUMN `dateIsAssumed` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_dateIsAssumed` ON `expenses` (`dateIsAssumed`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_incomes_dateIsAssumed` ON `incomes` (`dateIsAssumed`)")
    }
}

/**
 * Room migration from schema version 8 → 9.
 *
 * Records whether a debit was money spent or money moved, and rewrites the
 * category labels the app used to store.
 *
 * **Nature.** Transfers between the user's own accounts, credit-card bill
 * payments, EMI, investments and ATM withdrawals all appear on a statement as
 * debits. Counted as spending, one transfer makes a month look ruinous. Existing
 * rows default to Spending, which is what they were assumed to be — the column
 * cannot be inferred retrospectively, and guessing at it would silently rewrite
 * history the user has already seen.
 *
 * **Categories.** The category column holds the display label, and the labels
 * changed: "Food" became "Food & Dining", "Health" became "Health & Medical".
 * Left alone, every existing row would match no category and read as Other — a
 * user's whole history reclassified by an upgrade. "Bills" is the imprecise one;
 * it covered utilities, phone and subscriptions together, and Utilities is the
 * least wrong of those.
 *
 * Kotlin resolves these labels too (see ExpenseCategory.LEGACY_LABELS), so the
 * rewrite is belt and braces — but it keeps the stored data honest rather than
 * relying on every future reader to know the history.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `expenses` ADD COLUMN `nature` TEXT NOT NULL DEFAULT 'Spending'"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_nature` ON `expenses` (`nature`)")

        val renames = listOf(
            "Food" to "Food & Dining",
            "Health" to "Health & Medical",
            "Bills" to "Utilities"
        )
        for ((old, new) in renames) {
            db.execSQL("UPDATE `expenses` SET `category` = ? WHERE `category` = ?", arrayOf(new, old))
        }
    }
}

/**
 * Room migration from schema version 9 → 10.
 *
 * Prepares recurring entries to be found automatically rather than only typed in.
 *
 * **Currency.** An amount with no currency is ambiguous, and detection must
 * never average a rupee series together with a dollar one. Existing rows default
 * to INR, which is what the app has stored everywhere else by default.
 *
 * **Nature and category.** A recurring entry previously knew only its title,
 * amount and cadence, which is enough to display it and nothing else. Rent, a
 * loan EMI and a monthly investment are all "the same amount every month", but
 * one is consumption, one repays debt and one buys an asset — a single figure
 * summing all three describes none of them. Existing rows default to Spending
 * and Other: every entry that exists today was hand-entered, and inferring a
 * nature for it retrospectively would be guessing at the user's own money.
 *
 * **Source and confidence.** Detection produces guesses, and a guess the user
 * accepted should stay distinguishable from a fact they asserted. Existing rows
 * are MANUAL with confidence 1.0, which is exactly what they are.
 *
 * **Dismissals** get their own table rather than a flag on `recurring`. A
 * rejected pattern has no title, amount or cadence anyone stands behind, so
 * storing it as a recurring entry would mean inventing values that no screen
 * should show and no total should count. Recording what was rejected — the
 * amount and cadence, not just the merchant — lets a dismissal be revisited if
 * the pattern later changes into a genuine commitment.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `currency` TEXT NOT NULL DEFAULT 'INR'")
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `nature` TEXT NOT NULL DEFAULT 'Spending'")
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `category` TEXT NOT NULL DEFAULT 'Other'")
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'MANUAL'")
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `occurrenceCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `confidence` REAL NOT NULL DEFAULT 1.0")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `dismissed_recurring_candidates` (
                `signature` TEXT NOT NULL,
                `merchant` TEXT NOT NULL,
                `currency` TEXT NOT NULL,
                `nature` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `cadence` TEXT NOT NULL,
                `lastSeenAmount` REAL NOT NULL,
                `lastSeenOccurrenceDate` INTEGER NOT NULL,
                `dismissedAt` INTEGER NOT NULL,
                PRIMARY KEY(`signature`)
            )
            """.trimIndent()
        )
    }
}

/**
 * Room migration from schema version 10 → 11.
 *
 * Gives a commitment a life beyond "it exists".
 *
 * **Status.** A gym membership paused over the winter should stop being counted
 * and stop generating reminders without being deleted — deleting it would lose
 * what it costs and let detection re-suggest it from scratch. Existing rows are
 * ACTIVE, which is what every commitment recorded so far has been.
 *
 * **Scheduling.** [nextDueDate] is what a reminder fires from and
 * [lastOccurrenceDate] is what it is projected from, so both have to be stored
 * rather than recomputed — the payments that produced them may be spread across
 * imports that no longer agree.
 *
 * [dueDayOfMonth] looks redundant next to those and is not. A commitment due on
 * the 31st is paid on the 28th in February, and projecting the following month
 * from that date would leave it stuck on the 28th permanently: one short month
 * would walk it backwards through the calendar for good. Anchoring the day
 * separately is what keeps it on the 31st.
 *
 * All four are nullable or defaulted, so existing rows need no backfill: a
 * commitment with no linked payments genuinely has no last occurrence, and
 * inventing one would put a wrong date into a reminder.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'ACTIVE'")
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `lastOccurrenceDate` INTEGER")
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `nextDueDate` INTEGER")
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `dueDayOfMonth` INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_status` ON `recurring` (`status`)")
    }
}

/**
 * Room migration from schema version 11 → 12.
 *
 * Adds standing monthly limits per category.
 *
 * Named `category_budgets` rather than `budgets` because the app already has a
 * different notion of a budget — the home screen compares a whole month's
 * spending against income. That answers "am I living within my means"; this
 * answers "am I spending more on eating out than I meant to", and the two should
 * not be confusable at a glance in a schema.
 *
 * A limit belongs to a category *and* a currency, hence the unique index across
 * both: without it a rupee budget could be measured against dollar spending, and
 * two limits for the same category could coexist with no rule for which wins.
 *
 * The alert columns record the highest percentage already announced and the
 * month it belongs to. Both are needed rather than a timestamp, because the
 * reset is a calendar question — a budget starts afresh on the 1st however few
 * days have passed since the last alert.
 */
/**
 * Room migration from schema version 12 → 13.
 *
 * Lets a commitment hold a price change without applying it.
 *
 * The amount on a commitment is a figure the user agreed to, and it decides what
 * the app tells them they owe each month. When reconciliation sees a payment
 * that disagrees with it — a subscription that has gone up — overwriting it
 * would silently change a number they had confirmed. So the new figure is parked
 * in [pendingAmount] and put to them instead.
 *
 * [declinedAmount] remembers a change they refused. Without it, refusing would
 * last exactly until the next payment arrived at that price and the same
 * question would be asked every month.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `pendingAmount` REAL")
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `declinedAmount` REAL")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `category_budgets` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `category` TEXT NOT NULL,
                `monthlyLimit` REAL NOT NULL,
                `currency` TEXT NOT NULL,
                `lastAlertedThreshold` INTEGER NOT NULL,
                `lastAlertedMonth` TEXT,
                `isSynced` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_category_budgets_category_currency` " +
                "ON `category_budgets` (`category`, `currency`)"
        )
    }
}
