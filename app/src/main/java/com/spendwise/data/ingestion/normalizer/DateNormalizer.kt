package com.spendwise.data.ingestion.normalizer

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Which component comes first in an all-numeric date like 03/05/2025. */
enum class DayMonthOrder { DAY_FIRST, MONTH_FIRST, UNKNOWN }

data class ParsedDate(
    val millis: Long,
    /** True when the token could be read either way and we fell back to a document default. */
    val ambiguous: Boolean
)

object DateNormalizer {

    private val DATE_TOKEN = Regex(
        """\b(?:\d{4}[/.-]\d{1,2}[/.-]\d{1,2}|\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}|\d{1,2}[- ]?[A-Za-z]{3,9}[- ]?\d{2,4}|[A-Za-z]{3,9}\s+\d{1,2},?\s+\d{2,4})\b"""
    )

    private val ISO = Regex("""^(\d{4})[/.-](\d{1,2})[/.-](\d{1,2})$""")
    private val NUMERIC = Regex("""^(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4})$""")
    private val DAY_MONTH_NAME = Regex("""^(\d{1,2})[- ]?([A-Za-z]{3,9})[- ]?(\d{2,4})$""")
    private val MONTH_NAME_DAY = Regex("""^([A-Za-z]{3,9})\s+(\d{1,2}),?\s+(\d{2,4})$""")

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
    )

    /** Every date-looking token in the text, in order. */
    fun findTokens(text: String): List<String> =
        DATE_TOKEN.findAll(text).map { it.value }.toList()

    fun findFirstToken(text: String): String? = DATE_TOKEN.find(text)?.value

    /**
     * Decides day-first vs month-first for a whole document.
     *
     * Any single token with a first component above 12 settles it for the entire file, which is
     * why this must run over all tokens together rather than per line. Deciding per line is how
     * you end up reading 03/05 as 3 May and 05/13 as 13 May in the same statement.
     */
    fun resolveOrder(texts: List<String>): DayMonthOrder {
        var dayFirst = 0
        var monthFirst = 0

        for (text in texts) {
            for (token in findTokens(text)) {
                val match = NUMERIC.matchEntire(token.trim()) ?: continue
                val first = match.groupValues[1].toIntOrNull() ?: continue
                val second = match.groupValues[2].toIntOrNull() ?: continue
                if (first > 12 && second <= 12) dayFirst++
                if (second > 12 && first <= 12) monthFirst++
            }
        }

        return when {
            dayFirst > monthFirst -> DayMonthOrder.DAY_FIRST
            monthFirst > dayFirst -> DayMonthOrder.MONTH_FIRST
            else -> DayMonthOrder.UNKNOWN
        }
    }

    /** Backwards-compatible entry point. Prefer [parse] so you can see the ambiguity flag. */
    fun normalize(raw: String, order: DayMonthOrder = DayMonthOrder.DAY_FIRST): Long? =
        parse(raw, order)?.millis

    fun parse(raw: String, order: DayMonthOrder = DayMonthOrder.DAY_FIRST): ParsedDate? {
        val token = (DATE_TOKEN.find(raw)?.value ?: raw).trim()

        ISO.matchEntire(token)?.let { m ->
            return build(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt(), false)
        }

        DAY_MONTH_NAME.matchEntire(token)?.let { m ->
            val month = monthOf(m.groupValues[2]) ?: return@let
            return build(m.groupValues[1].toInt(), month, expandYear(m.groupValues[3].toInt()), false)
        }

        MONTH_NAME_DAY.matchEntire(token)?.let { m ->
            val month = monthOf(m.groupValues[1]) ?: return@let
            return build(m.groupValues[2].toInt(), month, expandYear(m.groupValues[3].toInt()), false)
        }

        NUMERIC.matchEntire(token)?.let { m ->
            val first = m.groupValues[1].toInt()
            val second = m.groupValues[2].toInt()
            val year = expandYear(m.groupValues[3].toInt())

            // A component above 12 can only be the day, so the token disambiguates itself and
            // the document-level default does not apply.
            return when {
                first > 12 && second <= 12 -> build(first, second, year, false)
                second > 12 && first <= 12 -> build(second, first, year, false)
                first > 12 && second > 12 -> null
                order == DayMonthOrder.MONTH_FIRST -> build(second, first, year, false)
                order == DayMonthOrder.DAY_FIRST -> build(first, second, year, false)
                // Genuinely ambiguous: read it day-first but say so, so callers can drop
                // confidence and route the row to review instead of silently guessing.
                else -> build(first, second, year, true)
            }
        }

        return null
    }

    /**
     * Excel stores dates as days since 1899-12-30 (the offset absorbs Lotus 1-2-3's
     * non-existent 1900-02-29). Spreadsheet exports hand us these as bare numbers.
     */
    fun fromExcelSerial(serial: Double): Long? =
        excelSerialToMillis(serial)?.takeIf { isPlausible(it) }

    /** Raw serial conversion with no plausibility gate, for callers that validate later. */
    fun excelSerialToMillis(serial: Double): Long? {
        if (serial < 1 || serial > 2_958_465) return null
        return ((serial - EXCEL_EPOCH_OFFSET_DAYS) * MILLIS_PER_DAY).toLong()
    }

    private fun monthOf(name: String): Int? =
        MONTHS[name.lowercase(Locale.US).take(3)]

    private fun expandYear(year: Int): Int = when {
        year >= 100 -> year
        // Two-digit years on financial documents are recent, never 19xx.
        year <= currentTwoDigitYear() + 1 -> 2000 + year
        else -> 1900 + year
    }

    private fun currentTwoDigitYear(): Int =
        Calendar.getInstance().get(Calendar.YEAR) % 100

    private fun build(day: Int, month: Int, year: Int, ambiguous: Boolean): ParsedDate? {
        if (month !in 1..12 || day !in 1..31) return null
        val calendar = Calendar.getInstance(TimeZone.getDefault()).apply {
            isLenient = false
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }
        val millis = try {
            calendar.timeInMillis
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (!isPlausible(millis)) return null
        return ParsedDate(millis, ambiguous)
    }

    /**
     * Rejects nonsense that would otherwise import silently — an OCR'd "31/12/2099", or a
     * reference number that happened to look like a date.
     */
    private fun isPlausible(millis: Long): Boolean {
        val now = System.currentTimeMillis()
        return millis in (now - MAX_PAST_MS)..(now + MAX_FUTURE_MS)
    }

    private const val MILLIS_PER_DAY = 86_400_000.0
    private const val EXCEL_EPOCH_OFFSET_DAYS = 25_569.0
    private const val MAX_PAST_MS = 20L * 365 * 24 * 60 * 60 * 1000
    private const val MAX_FUTURE_MS = 400L * 24 * 60 * 60 * 1000
}
