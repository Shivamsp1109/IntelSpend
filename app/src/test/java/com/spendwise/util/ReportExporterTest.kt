package com.spendwise.util

import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.DateRange
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.ExpenseReport
import com.spendwise.domain.model.ExpenseSource
import com.spendwise.domain.model.SpendingSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

/**
 * CSV field encoding.
 *
 * Worth pinning because both failure modes are silent. A stray comma shifts
 * every later column into the wrong header without any error, and a title
 * beginning '=' is a live formula the moment the file is opened — and titles
 * here come from parsed bank statements and receipts, not from the user typing.
 */
class ReportExporterTest {

    /** Fixed zone, so the date column does not depend on where the suite runs. */
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun expense(
        title: String = "Lunch",
        merchant: String? = "Ironhill",
        amount: Double = 1_450.5,
        category: ExpenseCategory = ExpenseCategory.FoodDining
    ) = Expense(
        id = 1,
        title = title,
        amount = amount,
        category = category,
        date = ELEVENTH_OF_AUGUST,
        merchant = merchant,
        currency = Currency.INR,
        source = ExpenseSource.MANUAL
    )

    // ── Escaping ──────────────────────────────────────────────────────────────

    @Test
    fun `ordinary text passes through untouched`() {
        assertEquals("Lunch", csvField("Lunch"))
        assertEquals("", csvField(""))
    }

    @Test
    fun `a comma forces quoting`() {
        assertEquals("\"Coffee, tea and cake\"", csvField("Coffee, tea and cake"))
    }

    @Test
    fun `embedded quotes are doubled inside a quoted field`() {
        assertEquals("\"He said \"\"hello\"\"\"", csvField("""He said "hello""""))
    }

    /** A newline inside an unquoted field silently starts a new record. */
    @Test
    fun `line breaks are quoted rather than left to split the row`() {
        assertEquals("\"Line one\nLine two\"", csvField("Line one\nLine two"))
        assertTrue(csvField("Carriage\rreturn").startsWith("\""))
    }

    // ── Formula injection ─────────────────────────────────────────────────────

    @Test
    fun `a leading equals sign is defused`() {
        assertEquals("'=1+1", csvField("=1+1"))
    }

    @Test
    fun `the other formula triggers are defused too`() {
        assertEquals("'+SUM(A1)", csvField("+SUM(A1)"))
        assertEquals("'-2+3", csvField("-2+3"))
        assertEquals("'@import", csvField("@import"))
    }

    /** The guard must survive quoting, not be undone by it. */
    @Test
    fun `a formula containing a comma is both defused and quoted`() {
        assertEquals("\"'=HYPERLINK(\"\"a\"\",\"\"b\"\")\"", csvField("""=HYPERLINK("a","b")"""))
    }

    @Test
    fun `an equals sign inside the text is harmless and left alone`() {
        assertEquals("Bill = paid", csvField("Bill = paid"))
    }

    // ── Rows ──────────────────────────────────────────────────────────────────

    @Test
    fun `a row has one field for every header column`() {
        val headerColumns = ReportExporter.CSV_HEADER.split(",").size
        val row = csvRow(expense(), dateFormat)

        assertEquals(headerColumns, row.split(",").size)
    }

    @Test
    fun `a row carries the fields in header order`() {
        val fields = csvRow(expense(), dateFormat).split(",")

        assertEquals("2026-08-11", fields[0])
        assertEquals("Lunch", fields[1])
        assertEquals("Ironhill", fields[2])
        assertEquals("Food & Dining", fields[3])
        assertEquals("1450.5", fields[4])
        assertEquals("INR", fields[5])
        assertEquals("MANUAL", fields[6])
    }

    /**
     * Amounts stay raw. Formatting them would put a thousands separator inside
     * the field and a currency symbol in front, and the spreadsheet would read
     * the column as text.
     */
    @Test
    fun `the amount column is a bare number`() {
        val amount = csvRow(expense(amount = 1_234_567.89), dateFormat).split(",")[4]

        assertEquals("1234567.89", amount)
        assertTrue(amount.none { it == ',' || it == '₹' })
    }

    @Test
    fun `a missing merchant leaves an empty field rather than the word null`() {
        val fields = csvRow(expense(merchant = null), dateFormat).split(",")

        assertEquals("", fields[2])
    }

    /** The reason quoting matters: a comma in a title must not shift the columns. */
    @Test
    fun `a comma in the title does not displace later columns`() {
        val row = csvRow(expense(title = "Dinner, drinks"), dateFormat)

        assertTrue(row, row.contains("\"Dinner, drinks\""))
        assertTrue(row.endsWith(",INR,MANUAL"))
    }

    // ── File naming ───────────────────────────────────────────────────────────

    private fun report(periodLabel: String) = ExpenseReport(
        periodLabel = periodLabel,
        currency = Currency.INR,
        generatedAt = ELEVENTH_OF_AUGUST,
        summary = SpendingSummary(
            currency = Currency.INR,
            range = DateRange(0, 1),
            totalExpense = 100.0,
            totalIncome = 0.0,
            previousExpense = 0.0,
            previousIncome = 0.0,
            transactionCount = 1
        ),
        byCategory = emptyList(),
        topMerchants = emptyList(),
        transactions = emptyList()
    )

    @Test
    fun `the file name identifies the app and the period`() {
        assertEquals("IntelSpend-August-2026", report("August 2026").fileNameStem)
    }

    /**
     * Period labels contain en dashes and spaces, and a filename cannot. The
     * separator collapses runs rather than replacing each character, so a label
     * does not turn into a row of hyphens.
     */
    @Test
    fun `characters a filesystem would reject are replaced`() {
        assertEquals("IntelSpend-1-10-Aug-2026", report("1 – 10 Aug 2026").fileNameStem)
        assertEquals("IntelSpend-2026", report("2026").fileNameStem)
    }

    @Test
    fun `no separator is left dangling at either end`() {
        val stem = report("  August 2026  ").fileNameStem

        assertTrue(stem, !stem.endsWith("-"))
        assertEquals("IntelSpend-August-2026", stem)
    }

    private companion object {
        /** Derived rather than written as an epoch constant, which is unreadable and easy to get wrong. */
        val ELEVENTH_OF_AUGUST: Long =
            LocalDate.of(2026, 8, 11).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
}
