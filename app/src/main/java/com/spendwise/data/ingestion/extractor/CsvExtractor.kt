package com.spendwise.data.ingestion.extractor

import com.spendwise.data.ingestion.model.FieldConfidence
import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.data.ingestion.model.TransactionType
import com.spendwise.data.ingestion.normalizer.AmountNormalizer
import com.spendwise.data.ingestion.normalizer.CurrencyNormalizer
import com.spendwise.data.ingestion.normalizer.DateNormalizer
import com.spendwise.data.ingestion.normalizer.DayMonthOrder
import com.spendwise.data.ingestion.normalizer.MerchantNormalizer
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.ExpenseSource
import javax.inject.Inject
import kotlin.math.abs

/**
 * Extracts transactions from tabular exports (CSV, TSV, XLSX).
 *
 * Column roles are matched most-specific-first with word boundaries. Both details matter: the
 * previous substring-based mapping sent "Debit Amount" and "Credit Amount" to the same generic
 * amount slot (so the credit column silently overwrote the debit column and every row with a
 * blank credit was dropped), and `contains("cr")` matched inside "Description".
 */
class CsvExtractor @Inject constructor() {

    fun extract(
        rows: List<List<String>>,
        source: ExpenseSource = ExpenseSource.CSV
    ): List<RawTransaction> {
        if (rows.isEmpty()) return emptyList()

        val headerIndex = findHeaderRow(rows)
        val fromHeader = headerIndex?.let { mapColumns(rows[it]) }

        // Header names are not dependable — they can be in another language, abbreviated past
        // recognition, or absent. Infer the missing roles from what the data actually contains.
        val inferred = if (fromHeader?.date == null) inferColumns(rows) else null
        val columns = if (inferred != null) merge(fromHeader, inferred.columns) else fromHeader
        if (columns?.date == null) return emptyList()

        val dataStart = headerIndex?.plus(1) ?: inferred?.firstDataRow ?: return emptyList()
        val dataRows = rows.drop(dataStart).filter { row -> row.any { it.isNotBlank() } }

        // Resolve dd/MM vs MM/dd across the whole file before parsing any single row.
        val order = DateNormalizer.resolveOrder(
            dataRows.mapNotNull { row -> columns.date.let { row.getOrNull(it) } }
        )

        val records = dataRows.mapNotNull { row -> parseRow(row, columns, order) }
        if (records.isEmpty()) return emptyList()

        return records.mapIndexed { index, record ->
            val previousBalance = records.take(index).lastOrNull { it.balance != null }?.balance
            toTransaction(record, previousBalance, columns, order, source)
        }
    }

    // ---------------------------------------------------------------- header

    private enum class Role {
        DATE, DESCRIPTION, AMOUNT, DEBIT, CREDIT, BALANCE, CURRENCY, CATEGORY, INDICATOR
    }

    private data class Columns(
        val date: Int?,
        val description: Int?,
        val amount: Int?,
        val debit: Int?,
        val credit: Int?,
        val balance: Int?,
        val currency: Int?,
        val category: Int?,
        val indicator: Int?
    )

    /**
     * Scores candidate rows instead of falling back to row 0. Bank exports carry several
     * preamble lines (account number, statement period), and treating one of those as the
     * header leaves every column unmapped and yields zero transactions.
     */
    private fun findHeaderRow(rows: List<List<String>>): Int? {
        val limit = minOf(rows.size, HEADER_SEARCH_DEPTH)
        var best: Int? = null
        var bestScore = 0

        for (i in 0 until limit) {
            val roles = rows[i].mapNotNull { roleOf(it) }.toSet()
            var score = roles.size
            if (Role.DATE in roles) score += 2
            if (Role.AMOUNT in roles || Role.DEBIT in roles || Role.CREDIT in roles) score += 2
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }

        // A real header names a date and at least one money column.
        return if (bestScore >= MIN_HEADER_SCORE) best else null
    }

    private fun mapColumns(header: List<String>): Columns {
        val byRole = mutableMapOf<Role, Int>()
        val dateCandidates = mutableListOf<Int>()
        val descCandidates = mutableListOf<Int>()

        header.forEachIndexed { index, raw ->
            when (val role = roleOf(raw)) {
                Role.DATE -> dateCandidates.add(index)
                Role.DESCRIPTION -> descCandidates.add(index)
                null -> Unit
                // First occurrence wins for money columns; statements sometimes repeat a
                // header in a continuation block.
                else -> byRole.putIfAbsent(role, index)
            }
        }

        val date = dateCandidates.minByOrNull { index ->
            val text = normalizeHeader(header[index])
            when {
                PREFERRED_DATE.containsMatchIn(text) -> 0
                text.contains("value") -> 2
                else -> 1
            }
        }
        val description = descCandidates.minByOrNull { index ->
            val text = normalizeHeader(header[index])
            if (PREFERRED_DESC.containsMatchIn(text)) 0 else 1
        }

        return Columns(
            date = date,
            description = description,
            amount = byRole[Role.AMOUNT],
            debit = byRole[Role.DEBIT],
            credit = byRole[Role.CREDIT],
            balance = byRole[Role.BALANCE],
            currency = byRole[Role.CURRENCY],
            category = byRole[Role.CATEGORY],
            indicator = byRole[Role.INDICATOR]
        )
    }

    private fun roleOf(raw: String): Role? {
        val text = normalizeHeader(raw)
        if (text.isEmpty()) return null
        return when {
            INDICATOR_HEADER.matches(text) -> Role.INDICATOR
            text.contains("balance") || text == "bal" -> Role.BALANCE
            DEBIT_HEADER.containsMatchIn(text) -> Role.DEBIT
            CREDIT_HEADER.containsMatchIn(text) -> Role.CREDIT
            DATE_HEADER.containsMatchIn(text) -> Role.DATE
            DESC_HEADER.containsMatchIn(text) -> Role.DESCRIPTION
            AMOUNT_HEADER.containsMatchIn(text) -> Role.AMOUNT
            text.contains("currency") || text == "ccy" -> Role.CURRENCY
            text.contains("category") -> Role.CATEGORY
            else -> null
        }
    }

    // ---------------------------------------------------------------- inference

    private data class Inferred(val firstDataRow: Int, val columns: Columns)

    private fun merge(preferred: Columns?, fallback: Columns): Columns = Columns(
        date = preferred?.date ?: fallback.date,
        description = preferred?.description ?: fallback.description,
        amount = preferred?.amount ?: fallback.amount,
        debit = preferred?.debit ?: fallback.debit,
        credit = preferred?.credit ?: fallback.credit,
        balance = preferred?.balance ?: fallback.balance,
        currency = preferred?.currency ?: fallback.currency,
        category = preferred?.category ?: fallback.category,
        indicator = preferred?.indicator ?: fallback.indicator
    )

    /**
     * Works out what each column holds by looking at the values rather than the header.
     *
     * This is what makes the parser survive a header in another language, or none at all: a
     * column that parses as a date on nearly every row is the date column, whatever it is
     * called. The balance column is identified the same way the statement extractor does it —
     * by checking whether consecutive differences show up as figures on the same row.
     */
    private fun inferColumns(rows: List<List<String>>): Inferred? {
        val firstDataRow = rows.indices.firstOrNull { i ->
            val row = rows[i]
            row.any { DateNormalizer.parse(it, DayMonthOrder.DAY_FIRST) != null } &&
                row.any { AmountNormalizer.parse(it)?.let { v -> v != 0.0 } == true }
        } ?: return null

        val dataRows = rows.drop(firstDataRow).filter { row -> row.any { it.isNotBlank() } }
        if (dataRows.isEmpty()) return null
        val width = dataRows.maxOf { it.size }

        val dateRatio = FloatArray(width)
        val moneyRatio = FloatArray(width)
        val letters = FloatArray(width)

        for (col in 0 until width) {
            val values = dataRows.mapNotNull { it.getOrNull(col)?.trim() }.filter { it.isNotBlank() }
            if (values.isEmpty()) continue
            dateRatio[col] = values.count {
                DateNormalizer.parse(it, DayMonthOrder.DAY_FIRST) != null
            }.toFloat() / values.size
            moneyRatio[col] = values.count {
                DateNormalizer.parse(it, DayMonthOrder.DAY_FIRST) == null &&
                    AmountNormalizer.parse(it) != null
            }.toFloat() / values.size
            letters[col] = values.sumOf { v -> v.count { it.isLetter() } }.toFloat() / values.size
        }

        val date = (0 until width).firstOrNull { dateRatio[it] >= CONSISTENT } ?: return null
        val numeric = (0 until width).filter { it != date && moneyRatio[it] >= CONSISTENT }
        val description = (0 until width)
            .filter { it != date && it !in numeric && letters[it] >= 3f }
            .maxByOrNull { letters[it] }

        val balance = numeric.lastOrNull { col -> behavesLikeRunningBalance(dataRows, col, numeric) }
        val money = numeric.filter { it != balance }

        return Inferred(
            firstDataRow = firstDataRow,
            columns = Columns(
                date = date,
                description = description,
                // Two money columns either side of a balance are the debit/credit pair; one is
                // a single signed or indicator-qualified amount.
                amount = if (money.size == 1) money.single() else null,
                debit = if (money.size == 2) money[0] else null,
                credit = if (money.size == 2) money[1] else null,
                balance = balance,
                currency = null,
                category = null,
                indicator = null
            )
        )
    }

    private fun behavesLikeRunningBalance(
        dataRows: List<List<String>>,
        col: Int,
        numeric: List<Int>
    ): Boolean {
        val values = dataRows.map { row ->
            row.getOrNull(col)?.let { AmountNormalizer.parse(it) }
        }
        var checked = 0
        var matched = 0
        var previousIndex = -1

        for (i in values.indices) {
            val current = values[i] ?: continue
            if (previousIndex >= 0) {
                val delta = abs(current - values[previousIndex]!!)
                if (delta >= 0.01) {
                    checked++
                    val others = numeric.filter { it != col }
                        .mapNotNull { dataRows[i].getOrNull(it)?.let { c -> AmountNormalizer.parse(c) } }
                    if (others.any { abs(abs(it) - delta) <= 0.02 }) matched++
                }
            }
            previousIndex = i
        }
        return checked >= 2 && matched.toFloat() / checked >= 0.6f
    }

    private fun normalizeHeader(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^a-z0-9/ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ---------------------------------------------------------------- rows

    private data class Record(
        val dateMillis: Long,
        val dateAmbiguous: Boolean,
        val description: String,
        val debit: Double?,
        val credit: Double?,
        val amount: Double?,
        val amountWasSigned: Boolean,
        val amountNegative: Boolean,
        val balance: Double?,
        val indicator: String?,
        val rowText: String,
        val currencyText: String,
        val categoryText: String?
    )

    private fun parseRow(row: List<String>, columns: Columns, order: DayMonthOrder): Record? {
        val dateCell = columns.date?.let { row.getOrNull(it) }?.trim().orEmpty()
        val parsed = parseDateCell(dateCell, order) ?: return null

        val debit = columns.debit?.let { cell(row, it) }?.let { AmountNormalizer.parse(it) }?.let { abs(it) }
        val credit = columns.credit?.let { cell(row, it) }?.let { AmountNormalizer.parse(it) }?.let { abs(it) }
        val amountRaw = columns.amount?.let { cell(row, it) }
        val amountParsed = amountRaw?.let { AmountNormalizer.parse(it) }
        val balance = columns.balance?.let { cell(row, it) }?.let { AmountNormalizer.parse(it) }

        if (debit == null && credit == null && amountParsed == null) return null

        return Record(
            dateMillis = parsed.millis,
            dateAmbiguous = parsed.ambiguous,
            description = columns.description?.let { row.getOrNull(it) }?.trim().orEmpty(),
            debit = debit?.takeIf { it > 0 },
            credit = credit?.takeIf { it > 0 },
            amount = amountParsed?.let { abs(it) }?.takeIf { it > 0 },
            amountWasSigned = amountRaw?.let { it.startsWith("-") || it.startsWith("+") } ?: false,
            amountNegative = (amountParsed ?: 0.0) < 0 ||
                (amountRaw?.startsWith("(") == true && amountRaw.endsWith(")")),
            balance = balance,
            indicator = columns.indicator?.let { row.getOrNull(it) }?.trim()?.uppercase(),
            rowText = row.joinToString(" "),
            currencyText = buildString {
                columns.currency?.let { row.getOrNull(it) }?.let { append(it).append(' ') }
                append(row.joinToString(" "))
            },
            categoryText = columns.category?.let { row.getOrNull(it) }
        )
    }

    private fun cell(row: List<String>, index: Int): String? =
        row.getOrNull(index)?.trim()?.takeIf { it.isNotBlank() && it != "-" }

    /**
     * Handles both textual dates and the bare day-counts spreadsheets store. An .xlsx date
     * cell arrives as something like "45678", which no date format would ever match.
     */
    private fun parseDateCell(raw: String, order: DayMonthOrder) =
        DateNormalizer.parse(raw, order)
            ?: raw.toDoubleOrNull()
                ?.takeIf { it >= MIN_EXCEL_SERIAL && it <= MAX_EXCEL_SERIAL }
                ?.let { serial ->
                    DateNormalizer.fromExcelSerial(serial)?.let {
                        com.spendwise.data.ingestion.normalizer.ParsedDate(it, false)
                    }
                }

    // ---------------------------------------------------------------- mapping

    private fun toTransaction(
        record: Record,
        previousBalance: Double?,
        columns: Columns,
        order: DayMonthOrder,
        source: ExpenseSource
    ): RawTransaction {
        val balanceDelta = if (record.balance != null && previousBalance != null) {
            record.balance - previousBalance
        } else {
            null
        }

        val amount = record.debit ?: record.credit ?: record.amount ?: 0.0
        val lowerRow = record.rowText.lowercase()

        // A balance column turns direction from a guess into arithmetic.
        val confirmedByBalance = balanceDelta
            ?.takeIf { abs(it) > 0.001 && abs(abs(it) - amount) <= 0.02 }

        val isCredit: Boolean
        val directionConfidence: Float

        when {
            confirmedByBalance != null -> {
                isCredit = confirmedByBalance > 0
                directionConfidence = 1.0f
            }
            record.debit != null -> {
                isCredit = false
                directionConfidence = 0.95f
            }
            record.credit != null -> {
                isCredit = true
                directionConfidence = 0.95f
            }
            record.indicator != null && INDICATOR_CREDIT.matches(record.indicator) -> {
                isCredit = true
                directionConfidence = 0.95f
            }
            record.indicator != null && INDICATOR_DEBIT.matches(record.indicator) -> {
                isCredit = false
                directionConfidence = 0.95f
            }
            record.amountNegative -> {
                isCredit = false
                directionConfidence = 0.9f
            }
            record.amountWasSigned -> {
                isCredit = true
                directionConfidence = 0.85f
            }
            CREDIT_ROW.containsMatchIn(lowerRow) -> {
                isCredit = true
                directionConfidence = 0.7f
            }
            DEBIT_ROW.containsMatchIn(lowerRow) -> {
                isCredit = false
                directionConfidence = 0.7f
            }
            else -> {
                // Unsigned single amount column with no other signal. Spending is the common
                // case, but flag it low so the row surfaces for review rather than importing
                // an income as an expense in silence.
                isCredit = false
                directionConfidence = 0.5f
            }
        }

        val merchant = MerchantNormalizer.normalizeDescription(record.description)
            .ifBlank { record.description.trim().ifBlank { "Unknown" } }

        val category = record.categoryText
            ?.let { ExpenseCategory.fromLabel(it) }
            ?: ExpenseCategory.Other

        val dateConfidence = when {
            record.dateAmbiguous -> 0.6f
            order == DayMonthOrder.UNKNOWN -> 0.82f
            else -> 0.97f
        }
        val merchantConfidence = if (merchant.length >= 4 && merchant.any { it.isLetter() }) 0.88f else 0.5f

        return RawTransaction(
            title = merchant.take(40),
            amount = amount,
            date = record.dateMillis,
            merchant = merchant,
            currency = CurrencyNormalizer.normalize(record.currencyText),
            category = category,
            type = if (isCredit) TransactionType.CREDIT else TransactionType.DEBIT,
            source = source,
            confidence = directionConfidence,
            fieldConfidence = FieldConfidence(
                title = merchantConfidence,
                amount = minOf(0.97f, directionConfidence),
                date = dateConfidence,
                merchant = merchantConfidence,
                currency = if (columns.currency != null) 0.95f else 0.85f,
                category = if (category != ExpenseCategory.Other) 0.9f else 0.55f
            )
        )
    }

    private companion object {
        const val HEADER_SEARCH_DEPTH = 25
        const val MIN_HEADER_SCORE = 5
        const val MIN_EXCEL_SERIAL = 20_000.0
        const val MAX_EXCEL_SERIAL = 60_000.0

        /** Share of rows a column must satisfy before we treat it as that type. */
        const val CONSISTENT = 0.7f

        // "Value Dt" and "Tran Dt" are common and contain neither the word "date" nor a bare "dt".
        val DATE_HEADER = Regex("""\b(date|dt|dte)\b""")
        val DEBIT_HEADER = Regex("""\b(debit|debits|withdrawal|withdrawals|withdrawn|paid out|dr)\b""")
        val CREDIT_HEADER = Regex("""\b(credit|credits|deposit|deposits|paid in|cr)\b""")
        val DESC_HEADER = Regex(
            """\b(desc|description|particular|particulars|narration|merchant|remarks|details|detail|memo|payee|reference)\b"""
        )
        val AMOUNT_HEADER = Regex("""\b(amount|amt|value|transaction amount)\b""")
        val INDICATOR_HEADER = Regex("""^(dr/cr|cr/dr|type|txn type|transaction type|debit/credit)$""")
        val PREFERRED_DATE = Regex("""\b(txn|trans|transaction|posting|post|book)\b""")
        val PREFERRED_DESC = Regex("""\b(narration|description|particulars|particular|merchant)\b""")
        val INDICATOR_CREDIT = Regex("""^(CR|CREDIT|C|\+)$""")
        val INDICATOR_DEBIT = Regex("""^(DR|DEBIT|D|-)$""")
        val CREDIT_ROW = Regex("""\b(credit|deposit|refund|received|reversal|cashback|interest|salary)\b""")
        val DEBIT_ROW = Regex("""\b(debit|withdrawal|paid|purchase|pos|atm)\b""")
    }
}
