package com.spendwise.data.ingestion.extractor

import com.spendwise.data.ingestion.geometry.Clustering
import com.spendwise.data.ingestion.geometry.RowGrouper
import com.spendwise.data.ingestion.geometry.TextRow
import com.spendwise.data.ingestion.model.FieldConfidence
import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.data.ingestion.model.TransactionType
import com.spendwise.data.ingestion.normalizer.AmountNormalizer
import com.spendwise.data.ingestion.normalizer.CurrencyNormalizer
import com.spendwise.data.ingestion.normalizer.DateNormalizer
import com.spendwise.data.ingestion.normalizer.DayMonthOrder
import com.spendwise.data.ingestion.normalizer.MerchantNormalizer
import com.spendwise.data.ingestion.ocr.OcrResult
import com.spendwise.data.ingestion.ocr.OcrWord
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseSource
import javax.inject.Inject
import kotlin.math.abs

/**
 * Extracts transactions from a bank or card statement laid out as a table.
 *
 * The design rests on one observation: a statement that prints a running balance validates
 * itself. If row N's balance minus row N-1's balance equals one of the numbers on row N, then
 * that number is the transaction amount and the sign of the difference is the direction — no
 * header parsing, no keyword guessing, no per-bank template. Everything else here (headers,
 * column clustering, CR/DR tokens) exists to handle statements that don't print a balance.
 */
class StatementExtractor @Inject constructor() {

    fun extract(ocrResult: OcrResult): List<RawTransaction> {
        val words = ocrResult.wordsOrApproximate()
        if (words.isEmpty()) return emptyList()

        val rows = RowGrouper.group(words)
            .filter { it.words.isNotEmpty() && !isNoise(it.text) }
        if (rows.isEmpty()) return emptyList()

        val order = DateNormalizer.resolveOrder(rows.map { normalizeSeparators(it.text) })
        val currency = CurrencyNormalizer.normalize(ocrResult.fullText)

        // Parsed once and indexed by row position. Re-deriving them per phase would produce
        // fresh objects that no longer compare equal to the ones held by the columns.
        val cellsByRow: List<List<NumberCell>> = rows.mapIndexed { index, row ->
            numberCellsIn(row, index)
        }
        val allCells = cellsByRow.flatten()
        if (allCells.isEmpty()) return emptyList()

        val columns = buildColumns(allCells)
        // Matching a header to a column is only meaningful against real coordinates. When the
        // words were approximated from line boxes, a column's x span is an artefact of how long
        // each row's text happened to be and will overlap several headers at once. Better to
        // have no column labels and fall back to the balance chain than to trust noise.
        val labels = if (ocrResult.hasRealGeometry) labelColumns(rows, columns) else emptyMap()

        val balanceColumn = detectBalanceColumn(rows, columns, labels, cellsByRow)
        val debitColumn = columns.firstOrNull { labels[it] == ColumnRole.DEBIT && it !== balanceColumn }
        val creditColumn = columns.firstOrNull { labels[it] == ColumnRole.CREDIT && it !== balanceColumn }

        val entries = buildEntries(rows, order, columns, balanceColumn, cellsByRow)
        if (entries.isEmpty()) return emptyList()

        return entries.mapIndexed { index, entry ->
            val previousBalance = entries.take(index).lastOrNull { it.balance != null }?.balance
            toTransaction(
                entry = entry,
                previousBalance = previousBalance,
                debitColumn = debitColumn,
                creditColumn = creditColumn,
                currency = currency,
                orderAmbiguous = order == DayMonthOrder.UNKNOWN
            )
        }
    }

    // ---------------------------------------------------------------- number cells

    private data class NumberCell(
        val rowIndex: Int,
        val value: Double,
        val rawText: String,
        val left: Float,
        val right: Float,
        val hasDecimal: Boolean,
        val explicitCredit: Boolean,
        val explicitDebit: Boolean,
        val negative: Boolean
    )

    private fun numberCellsIn(row: TextRow, rowIndex: Int): List<NumberCell> {
        val dateToken = DateNormalizer.findFirstToken(normalizeSeparators(row.text))
        val cells = mutableListOf<NumberCell>()

        row.words.forEachIndexed { index, word ->
            val cleaned = word.text.trim().trim(',', ';', '|', '(', ')')
            if (cleaned.isEmpty()) return@forEachIndexed
            if (dateToken != null && cleaned.length > 1 && dateToken.contains(cleaned)) return@forEachIndexed
            if (DateNormalizer.findFirstToken(cleaned) != null) return@forEachIndexed

            val match = AmountNormalizer.extractAmountMatches(cleaned, allowInteger = true)
                .singleOrNull() ?: return@forEachIndexed
            // A long run of digits with no decimal part is a reference number, not money.
            val digits = match.rawText.count { it.isDigit() }
            if (!match.hasDecimal && digits >= 7) return@forEachIndexed

            val suffix = row.words.getOrNull(index + 1)?.text?.uppercase()?.trim(' ', '.', ',') ?: ""
            val inline = cleaned.uppercase()

            cells.add(
                NumberCell(
                    rowIndex = rowIndex,
                    value = match.value,
                    rawText = match.rawText,
                    left = word.left,
                    right = word.right,
                    hasDecimal = match.hasDecimal,
                    explicitCredit = suffix == "CR" || inline.endsWith("CR"),
                    explicitDebit = suffix == "DR" || inline.endsWith("DR"),
                    negative = cleaned.startsWith("-") || (cleaned.startsWith("(") && cleaned.endsWith(")"))
                )
            )
        }
        return cells
    }

    // ---------------------------------------------------------------- columns

    private class Column(val cells: List<NumberCell>) {
        val rightEdge: Float = cells.map { it.right }.average().toFloat()
        val left: Float = cells.minOf { it.left }
        val right: Float = cells.maxOf { it.right }
        fun cellFor(rowIndex: Int): NumberCell? = cells.firstOrNull { it.rowIndex == rowIndex }
    }

    private enum class ColumnRole { DEBIT, CREDIT, BALANCE, AMOUNT }

    /**
     * Columns come from clustering the right edges of the numbers, not from the header.
     * Money in a statement table is right-aligned, so the right edge is stable even when
     * descriptions vary wildly in length — and it works on statements with no header at all.
     */
    private fun buildColumns(cells: List<NumberCell>): List<Column> {
        val widths = cells.map { it.right - it.left }.filter { it > 0f }.sorted()
        val medianWidth = if (widths.isEmpty()) 40f else widths[widths.size / 2]
        val tolerance = (medianWidth * 0.5f).coerceAtLeast(6f)

        return Clustering.cluster(cells.map { it.right }, tolerance)
            .mapNotNull { group ->
                val lo = group.min()
                val hi = group.max()
                cells.filter { it.right in lo..hi }.takeIf { it.isNotEmpty() }?.let { Column(it) }
            }
            .sortedBy { it.rightEdge }
    }

    private fun labelColumns(rows: List<TextRow>, columns: List<Column>): Map<Column, ColumnRole> {
        val headerWords = rows.flatMap { row ->
            row.words.mapNotNull { word -> roleOfHeaderWord(word.text)?.let { word to it } }
        }
        if (headerWords.isEmpty()) return emptyMap()

        val labels = mutableMapOf<Column, ColumnRole>()
        for (column in columns) {
            // Header text may be left- or centre-aligned above right-aligned figures, so a
            // small offset is expected — but the tolerance has to stay well under the gap
            // between columns. Too generous and a cheque-number column two columns away gets
            // labelled Debit, which then supplies the amount on any row the balance can't
            // confirm.
            val tolerance = (column.right - column.left) * HEADER_MATCH_RATIO
            headerWords
                .map { (word, role) -> Triple(word, role, horizontalDistance(word, column)) }
                .filter { it.third <= tolerance }
                .minByOrNull { it.third }
                ?.let { labels[column] = it.second }
        }
        return labels
    }

    private fun roleOfHeaderWord(raw: String): ColumnRole? =
        when (raw.lowercase().trim(' ', '.', ':', '(', ')')) {
            "debit", "withdrawal", "withdrawals", "withdrawn", "dr", "paid", "spend", "spends" -> ColumnRole.DEBIT
            "credit", "deposit", "deposits", "cr", "received" -> ColumnRole.CREDIT
            "balance", "bal" -> ColumnRole.BALANCE
            "amount", "amt", "value" -> ColumnRole.AMOUNT
            else -> null
        }

    private fun horizontalDistance(word: OcrWord, column: Column): Float = when {
        word.right < column.left -> column.left - word.right
        word.left > column.right -> word.left - column.right
        else -> 0f
    }

    /**
     * Finds the running-balance column.
     *
     * The header is only a hint. The decisive test is behavioural: in a real balance column the
     * change from one row to the next equals a figure printed on that same row. Nothing else in
     * a statement does that, so a column that passes is a balance column even if the header was
     * missed, and a column that fails is not one even if it says "Balance".
     */
    private fun detectBalanceColumn(
        rows: List<TextRow>,
        columns: List<Column>,
        labels: Map<Column, ColumnRole>,
        cellsByRow: List<List<NumberCell>>
    ): Column? {
        val behavioural = columns.filter { it.cells.size >= 3 && behavesLikeRunningBalance(it, cellsByRow) }
        if (behavioural.isNotEmpty()) return behavioural.maxByOrNull { it.rightEdge }

        // A statement prints the balance last on the row. That ordinal fact survives even when
        // the x positions are approximations (OCR gave us line boxes rather than words), where
        // clustering on those positions is meaningless.
        val lastInRow = lastCellPerRow(cellsByRow)
        if (lastInRow != null &&
            lastInRow.cells.size >= 3 &&
            behavesLikeRunningBalance(lastInRow, cellsByRow)
        ) {
            return lastInRow
        }

        val declaresBalance = rows.any { row ->
            row.words.any { roleOfHeaderWord(it.text) == ColumnRole.BALANCE }
        }
        if (declaresBalance) {
            val labelled = columns.filter { labels[it] == ColumnRole.BALANCE }
            if (labelled.size == 1) return labelled.single()
            // The header says there is a balance but the geometry can't say which column it is.
            // Fall back to position rather than picking one of several arbitrarily.
            if (lastInRow != null) return lastInRow
        }

        // Last resort: with three or more money columns the rightmost is conventionally the
        // balance. With fewer, assume there is none rather than sacrificing a real column.
        return if (columns.size >= 3) columns.last() else null
    }

    /** Rightmost figure of every row that prints more than one. */
    private fun lastCellPerRow(cellsByRow: List<List<NumberCell>>): Column? =
        cellsByRow
            .filter { it.size >= 2 }
            .mapNotNull { row -> row.maxByOrNull { it.right } }
            .takeIf { it.isNotEmpty() }
            ?.let { Column(it) }

    private fun behavesLikeRunningBalance(
        column: Column,
        cellsByRow: List<List<NumberCell>>
    ): Boolean {
        val ordered = column.cells.sortedBy { it.rowIndex }
        if (ordered.size < 3) return false

        var checked = 0
        var matched = 0
        for (i in 1 until ordered.size) {
            val delta = abs(ordered[i].value - ordered[i - 1].value)
            if (delta < 0.01) continue
            checked++
            val onRow = cellsByRow.getOrNull(ordered[i].rowIndex).orEmpty()
            if (onRow.any { abs(it.value - delta) <= 0.02 }) matched++
        }
        return checked >= 2 && matched.toFloat() / checked >= 0.6f
    }

    // ---------------------------------------------------------------- entries

    private data class Entry(
        val rowIndex: Int,
        val rowText: String,
        val dateMillis: Long,
        val dateAmbiguous: Boolean,
        val description: String,
        val amountCells: List<NumberCell>,
        val balance: Double?
    )

    private fun buildEntries(
        rows: List<TextRow>,
        order: DayMonthOrder,
        columns: List<Column>,
        balanceColumn: Column?,
        cellsByRow: List<List<NumberCell>>
    ): List<Entry> {
        val entries = mutableListOf<Entry>()

        rows.forEachIndexed { index, row ->
            val token = DateNormalizer.findFirstToken(normalizeSeparators(row.text))
            val parsed = token?.let { DateNormalizer.parse(it, order) }
            val cells = cellsByRow[index]

            if (parsed == null) {
                // No date: either a wrapped continuation of the previous transaction's
                // narration, or page furniture. Continuations carry no figures of their own,
                // so the whole row is description.
                val previous = entries.lastOrNull()
                if (previous != null && cells.isEmpty() &&
                    rows[previous.rowIndex].page == row.page &&
                    !isHeaderRow(row)
                ) {
                    val extra = row.text.trim()
                    if (extra.isNotEmpty() && extra.any { it.isLetter() }) {
                        entries[entries.lastIndex] =
                            previous.copy(description = "${previous.description} $extra".trim())
                    }
                }
                return@forEachIndexed
            }
            if (cells.isEmpty()) return@forEachIndexed

            val balanceCell = balanceColumn?.cellFor(index)
            val amountCells = cells.filter { it !== balanceCell }
            if (amountCells.isEmpty()) return@forEachIndexed

            entries.add(
                Entry(
                    rowIndex = index,
                    rowText = row.text,
                    dateMillis = parsed.millis,
                    dateAmbiguous = parsed.ambiguous,
                    // The boundary has to come from this row's own figures. A boundary shared
                    // across rows truncates the description of any row whose text runs longer.
                    description = buildDescription(row, cells.minOf { it.left }, token),
                    amountCells = amountCells,
                    balance = balanceCell?.value
                )
            )
        }

        return entries.filter { entry -> entry.description.count { it.isLetterOrDigit() } >= 3 }
    }

    /**
     * A repeated header at the top of page two carries no date and no figures, so it looks
     * exactly like a wrapped narration. Left unchecked it gets appended to the last transaction
     * of the previous page.
     */
    private fun isHeaderRow(row: TextRow): Boolean =
        row.words.count { roleOfHeaderWord(it.text) != null } >= 2

    private fun buildDescription(row: TextRow, boundary: Float, dateToken: String?): String {
        var text = row.textLeftOf(boundary).ifBlank { row.text }
        if (dateToken != null) text = text.replace(dateToken, " ")
        return text
            .replace(Regex("""\b\d{1,2}[/.-]\d{1,2}([/.-]\d{2,4})?\b"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ',', '.')
    }

    // ---------------------------------------------------------------- mapping

    private fun toTransaction(
        entry: Entry,
        previousBalance: Double?,
        debitColumn: Column?,
        creditColumn: Column?,
        currency: Currency,
        orderAmbiguous: Boolean
    ): RawTransaction {
        val balanceDelta = if (entry.balance != null && previousBalance != null) {
            entry.balance - previousBalance
        } else {
            null
        }

        // Prefer the figure the balance movement confirms. When it agrees, both the amount and
        // the direction are known rather than inferred.
        val confirmed = balanceDelta
            ?.takeIf { abs(it) > 0.001 }
            ?.let { delta -> entry.amountCells.firstOrNull { abs(it.value - abs(delta)) <= 0.02 } }

        val debitCell = debitColumn?.cellFor(entry.rowIndex)
        val creditCell = creditColumn?.cellFor(entry.rowIndex)

        // Rightmost wins among the remaining fallbacks: reference and cheque-number columns sit
        // to the left of the money columns in every statement layout.
        val chosen = confirmed
            ?: entry.amountCells.firstOrNull { it === debitCell }
            ?: entry.amountCells.firstOrNull { it === creditCell }
            ?: entry.amountCells.filter { it.hasDecimal }.maxByOrNull { it.right }
            ?: entry.amountCells.maxByOrNull { it.right }
            ?: entry.amountCells.first()

        val inCredit = chosen === creditCell
        val inDebit = chosen === debitCell

        val isCredit = when {
            confirmed != null && balanceDelta != null -> balanceDelta > 0
            inCredit -> true
            inDebit -> false
            chosen.explicitCredit -> true
            chosen.explicitDebit -> false
            chosen.negative -> false
            else -> CREDIT_KEYWORDS.containsMatchIn(entry.rowText.uppercase())
        }

        val merchant = MerchantNormalizer.normalizeDescription(entry.description)
            .ifBlank { entry.description.take(40) }

        val amountConfidence = when {
            confirmed != null -> 0.99f
            inDebit || inCredit -> 0.92f
            entry.amountCells.size == 1 && chosen.hasDecimal -> 0.88f
            chosen.hasDecimal -> 0.72f
            else -> 0.6f
        }
        val directionConfidence = when {
            confirmed != null -> 1.0f
            inCredit || inDebit -> 0.9f
            chosen.explicitCredit || chosen.explicitDebit || chosen.negative -> 0.85f
            else -> 0.55f
        }
        val dateConfidence = when {
            entry.dateAmbiguous -> 0.6f
            orderAmbiguous -> 0.8f
            else -> 0.95f
        }
        val merchantConfidence =
            if (merchant.length >= 4 && merchant.any { it.isLetter() }) 0.85f else 0.5f

        return RawTransaction(
            title = merchant.take(40),
            amount = chosen.value,
            date = entry.dateMillis,
            merchant = merchant,
            currency = currency,
            type = if (isCredit) TransactionType.CREDIT else TransactionType.DEBIT,
            source = ExpenseSource.PDF,
            confidence = (amountConfidence + dateConfidence + merchantConfidence + directionConfidence) / 4f,
            fieldConfidence = FieldConfidence(
                title = merchantConfidence,
                amount = minOf(amountConfidence, directionConfidence),
                date = dateConfidence,
                merchant = merchantConfidence,
                currency = 0.9f,
                category = 0.6f
            )
        )
    }

    // ---------------------------------------------------------------- helpers

    /** Collapses "12 / 01 / 2025" back to "12/01/2025" so date matching survives word splits. */
    private fun normalizeSeparators(text: String): String =
        text.replace(Regex("""(\d)\s*([/.-])\s*(\d)"""), "$1$2$3")
            .replace(Regex("""(\d)\s*([/.-])\s*(\d)"""), "$1$2$3")

    private fun isNoise(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isEmpty()) return true
        if (CLOCK.matches(lower)) return true
        return NOISE_PHRASES.any { lower.contains(it) }
    }

    private companion object {
        /** Header-to-column slack, as a fraction of the column's own width. */
        const val HEADER_MATCH_RATIO = 0.6f

        val CLOCK = Regex("""^\d{1,2}:\d{2}(\s*[ap]m)?$""")
        val CREDIT_KEYWORDS =
            Regex("""\b(CR|CREDIT|DEPOSIT|REFUND|RECEIVED|REVERSAL|CASHBACK|INTEREST|SALARY|PAYROLL)\b""")
        val NOISE_PHRASES = listOf(
            "page ", " of ", "statement period", "opening balance", "closing balance",
            "brought forward", "carried forward", "b/f", "c/f", "total debits", "total credits",
            "this is a computer generated", "registered office", "customer service",
            "please examine", "ifsc", "micr", "branch code", "nomination"
        )
    }
}
