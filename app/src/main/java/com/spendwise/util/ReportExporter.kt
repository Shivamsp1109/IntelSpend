package com.spendwise.util

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseReport
import com.spendwise.domain.model.ReportFormat
import com.spendwise.util.crypto.KeystoreCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed class SaveResult {
    /** [fileName] identifies the stored report so it can be shared later. */
    data class Saved(val fileName: String) : SaveResult()
    data class Failed(val message: String) : SaveResult()
}

/**
 * Builds and stores a period's report.
 *
 * Reports are the densest financial artefact this app produces — one file with
 * every transaction, merchant and amount for a period. They are therefore kept
 * inside app-private storage rather than the shared Downloads folder, and
 * encrypted at rest under a hardware-backed key, so a file manager, another
 * app, or someone browsing the device's storage finds nothing readable.
 *
 * That does mean a saved report is not directly openable from a file browser.
 * [shareableCopy] is the deliberate way out: it decrypts to a short-lived copy
 * in cache and hands it to the system share sheet, so the user can put the file
 * wherever they actually want it while nothing unencrypted lingers on disk.
 */
@Singleton
class ReportExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreCrypto: KeystoreCrypto
) {

    fun write(out: OutputStream, report: ExpenseReport, format: ReportFormat) = when (format) {
        ReportFormat.CSV -> writeCsv(out, report)
        ReportFormat.PDF -> writePdf(out, report)
    }

    /**
     * Renders the report and stores it encrypted in app-private storage.
     *
     * Rendered to a buffer first rather than streamed straight to the file: the
     * whole point is that no plaintext copy of this ever exists on disk, and a
     * streaming cipher that failed mid-write could leave a partial one behind.
     */
    fun save(report: ExpenseReport, format: ReportFormat): SaveResult {
        val fileName = uniqueName("${report.fileNameStem}.${format.extension}")

        return try {
            val plaintext = ByteArrayOutputStream().use { buffer ->
                write(buffer, report, format)
                buffer.toByteArray()
            }

            reportsDir().also { it.mkdirs() }
                .resolve(fileName + ENCRYPTED_SUFFIX)
                .writeBytes(keystoreCrypto.encrypt(REPORT_KEY_ALIAS, plaintext))

            SaveResult.Saved(fileName)
        } catch (e: Exception) {
            Log.w(TAG, "Saving $fileName failed.", e)
            SaveResult.Failed("Could not save the report.")
        }
    }

    /**
     * Decrypts a stored report into cache and returns a Uri the share sheet can
     * read, or null if it is missing or fails its authentication check.
     *
     * The decrypted copy lives in cache under a FileProvider path, so it is
     * still not world-readable — only the app the user picks gets a grant, and
     * only for that file. [clearSharedCopies] removes them afterwards.
     */
    fun shareableCopy(fileName: String): Uri? = try {
        val stored = reportsDir().resolve(fileName + ENCRYPTED_SUFFIX)
        if (!stored.exists()) {
            null
        } else {
            val plaintext = keystoreCrypto.decrypt(REPORT_KEY_ALIAS, stored.readBytes())
            val shared = sharedDir().also { it.mkdirs() }.resolve(fileName)
            shared.writeBytes(plaintext)

            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shared)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Preparing $fileName for sharing failed.", e)
        null
    }

    /** Deletes decrypted working copies; safe to call whenever the app resumes. */
    fun clearSharedCopies() {
        runCatching { sharedDir().listFiles()?.forEach { it.delete() } }
    }

    fun storedReports(): List<String> =
        reportsDir().listFiles()
            .orEmpty()
            .filter { it.name.endsWith(ENCRYPTED_SUFFIX) }
            .map { it.name.removeSuffix(ENCRYPTED_SUFFIX) }
            .sortedDescending()

    private fun reportsDir() = File(context.filesDir, REPORTS_DIR)

    private fun sharedDir() = File(context.cacheDir, SHARED_DIR)

    /** Keeps last month's report rather than overwriting it with this one. */
    private fun uniqueName(fileName: String): String {
        val stem = fileName.substringBeforeLast('.')
        val extension = fileName.substringAfterLast('.')
        val dir = reportsDir()

        var candidate = fileName
        var index = 1
        while (dir.resolve(candidate + ENCRYPTED_SUFFIX).exists()) {
            candidate = "$stem ($index).$extension"
            index++
        }
        return candidate
    }

    /**
     * Charset is explicit because the platform default is not UTF-8 everywhere,
     * and an earlier export mangled every ₹ it wrote. The byte-order mark is
     * there for Excel, which otherwise reads a UTF-8 CSV as the local codepage
     * and shows the same mojibake.
     */
    private fun writeCsv(out: OutputStream, report: ExpenseReport) {
        OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
            writer.write(BOM_CODE_POINT)
            writer.appendLine(CSV_HEADER)
            report.transactions.forEach { expense ->
                writer.appendLine(expense.toCsvRow())
            }
        }
    }

    private fun Expense.toCsvRow(): String = csvRow(this, isoDate)

    private fun writePdf(out: OutputStream, report: ExpenseReport) {
        val document = PdfDocument()
        try {
            val writer = PdfWriter(document, report)
            writer.drawHeader()
            writer.drawSummary()
            writer.drawCategoryBreakdown()
            writer.drawTransactions()
            writer.finish()
            document.writeTo(out)
        } finally {
            // Held native pages leak if the write throws part-way through.
            document.close()
        }
    }

    /**
     * Lays the report out across as many pages as it needs.
     *
     * The previous version drew the first 35 expenses onto a single page and
     * silently dropped the rest, which is the worst possible failure for a
     * financial report — it looks complete.
     */
    private class PdfWriter(
        private val document: PdfDocument,
        private val report: ExpenseReport
    ) {
        private val title = Paint().apply { textSize = 20f; isFakeBoldText = true }
        private val heading = Paint().apply { textSize = 12f; isFakeBoldText = true }
        private val body = Paint().apply { textSize = 10f }
        private val muted = Paint().apply { textSize = 9f; color = 0xFF6B6B6B.toInt() }
        private val rule = Paint().apply { strokeWidth = 0.6f; color = 0xFFCCCCCC.toInt() }

        private var pageNumber = 0

        // Declared before `page`, because startPage() sets it and initialisers
        // run in source order — the other way round it would be reset to zero
        // and the first page would draw off the top edge.
        private var y = TOP
        private var page: PdfDocument.Page = startPage()

        fun drawHeader() {
            page.canvas.drawText("SpendWise report", LEFT, y, title)
            y += 22f
            page.canvas.drawText(report.periodLabel, LEFT, y, heading)
            y += 14f
            page.canvas.drawText(
                "Generated ${generatedFormat.format(Date(report.generatedAt))} · " +
                    "amounts in ${report.currency.code}",
                LEFT, y, muted
            )
            y += 20f
            horizontalRule()
        }

        fun drawSummary() {
            section("Summary")
            val summary = report.summary
            listOfNotNull(
                "Total spent" to money(summary.totalExpense),
                "Transactions" to summary.transactionCount.toString(),
                "Average per day" to money(summary.averagePerDay),
                ("Total income" to money(summary.totalIncome)).takeIf { summary.totalIncome > 0.0 },
                ("Net" to money(summary.net)).takeIf { summary.totalIncome > 0.0 },
                summary.savingsRate?.let { "Savings rate" to "${(it * 100).toInt()}%" }
            ).forEach { (label, value) ->
                ensureRoom()
                page.canvas.drawText(label, LEFT, y, body)
                drawRightAligned(value, body)
                y += LINE_HEIGHT
            }
            y += 8f
        }

        fun drawCategoryBreakdown() {
            if (report.byCategory.isEmpty()) return
            section("By category")
            report.byCategory.forEach { (category, total) ->
                ensureRoom()
                page.canvas.drawText(category.label, LEFT, y, body)
                drawRightAligned(money(total), body)
                y += LINE_HEIGHT
            }
            y += 8f
        }

        fun drawTransactions() {
            if (report.transactions.isEmpty()) {
                section("Transactions")
                ensureRoom()
                page.canvas.drawText("No transactions in this period.", LEFT, y, muted)
                y += LINE_HEIGHT
                return
            }

            section("Transactions (${report.transactions.size})")
            drawTransactionHeader()
            report.transactions.forEach { expense ->
                if (ensureRoom()) drawTransactionHeader()
                page.canvas.drawText(isoDate.format(Date(expense.date)), LEFT, y, body)
                page.canvas.drawText(expense.category.label.ellipsised(CATEGORY_WIDTH), CATEGORY_X, y, body)
                page.canvas.drawText(expense.describe().ellipsised(DESCRIPTION_WIDTH), DESCRIPTION_X, y, body)
                drawRightAligned(money(expense.amount), body)
                y += LINE_HEIGHT
            }
        }

        fun finish() {
            drawFooter()
            document.finishPage(page)
        }

        // ── Layout plumbing ───────────────────────────────────────────────────

        private fun startPage(): PdfDocument.Page {
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            y = TOP
            return document.startPage(info)
        }

        /**
         * Breaks to a new page when [needed] vertical space would not fit.
         * Callers that are about to draw a heading ask for more, so a section
         * title never ends up stranded alone at the foot of a page.
         */
        private fun ensureRoom(needed: Float = LINE_HEIGHT): Boolean {
            if (y + needed <= BOTTOM) return false
            drawFooter()
            document.finishPage(page)
            page = startPage()
            return true
        }

        private fun drawFooter() {
            page.canvas.drawText("Page $pageNumber", LEFT, PAGE_HEIGHT - 28f, muted)
        }

        private fun section(name: String) {
            ensureRoom(needed = SECTION_MIN_ROOM)
            y += 6f
            page.canvas.drawText(name, LEFT, y, heading)
            y += 6f
            horizontalRule()
        }

        private fun horizontalRule() {
            page.canvas.drawLine(LEFT, y, RIGHT, y, rule)
            y += 14f
        }

        private fun drawTransactionHeader() {
            page.canvas.drawText("Date", LEFT, y, muted)
            page.canvas.drawText("Category", CATEGORY_X, y, muted)
            page.canvas.drawText("Description", DESCRIPTION_X, y, muted)
            drawRightAligned("Amount", muted)
            y += LINE_HEIGHT
        }

        private fun drawRightAligned(text: String, paint: Paint) {
            page.canvas.drawText(text, RIGHT - paint.measureText(text), y, paint)
        }

        /** Trims to fit rather than overprinting the column to its right. */
        private fun String.ellipsised(maxWidth: Float): String {
            if (body.measureText(this) <= maxWidth) return this
            var cut = length
            while (cut > 1 && body.measureText(substring(0, cut) + "…") > maxWidth) cut--
            return substring(0, cut) + "…"
        }

        private fun Expense.describe(): String {
            val payee = merchant?.takeIf { it.isNotBlank() }
            return when {
                payee == null -> title
                payee.equals(title, ignoreCase = true) -> title
                else -> "$title · $payee"
            }
        }

        private fun money(amount: Double) = CurrencyFormatter.format(amount, report.currency)

        private companion object {
            // A4 at 72dpi, the unit PdfDocument works in.
            const val PAGE_WIDTH = 595
            const val PAGE_HEIGHT = 842
            const val LEFT = 40f
            const val RIGHT = 555f
            const val TOP = 56f
            const val BOTTOM = 780f
            const val LINE_HEIGHT = 16f

            /** Heading, rule and at least two rows, or the heading moves on. */
            const val SECTION_MIN_ROOM = 4 * LINE_HEIGHT
            const val CATEGORY_X = 130f
            const val CATEGORY_WIDTH = 75f
            const val DESCRIPTION_X = 215f
            const val DESCRIPTION_WIDTH = 240f

            val generatedFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        }
    }

    companion object {
        const val CSV_HEADER = "Date,Title,Merchant,Category,Amount,Currency,Source"

        private const val TAG = "ReportExporter"

        /** Under filesDir — app-private, and excluded from Auto Backup. */
        private const val REPORTS_DIR = "reports"

        /** Under cacheDir — decrypted copies, cleared after sharing. */
        private const val SHARED_DIR = "shared_reports"

        /** Named so the stored bytes are never mistaken for a readable PDF or CSV. */
        private const val ENCRYPTED_SUFFIX = ".enc"

        private const val REPORT_KEY_ALIAS = "spendwise_report_key"

        /** Written as a code point: an invisible literal is too easy to lose in an edit. */
        private const val BOM_CODE_POINT = 0xFEFF

        /** ISO order, so spreadsheets parse it as a date and sort it correctly. */
        private val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}

/**
 * One CSV row, in the column order of [ReportExporter.CSV_HEADER].
 *
 * The date format is a parameter rather than a constant so this stays a pure
 * function of its inputs and can be checked without depending on the machine's
 * time zone.
 */
internal fun csvRow(expense: Expense, dateFormat: DateFormat): String = listOf(
    dateFormat.format(Date(expense.date)),
    csvField(expense.title),
    csvField(expense.merchant ?: ""),
    csvField(expense.category.label),
    // Numbers stay unformatted and unguarded: a spreadsheet needs to read these
    // as numbers, and thousands separators or a leading quote stop it.
    expense.amount.toString(),
    expense.currency.code,
    expense.source.name
).joinToString(",")

/**
 * Quoted whenever a delimiter, quote or line break would otherwise break the
 * row, and defused when the value could be read as a formula.
 *
 * The second part matters here because titles and merchant names come out of
 * parsed statements and receipts rather than being typed by the user: a
 * description beginning '=' is a live formula in Excel and Sheets, and opening
 * your own spending report should not execute anything.
 */
internal fun csvField(value: String): String {
    val defused = if (value.isNotEmpty() && value.first() in FORMULA_TRIGGERS) "'$value" else value
    return if (defused.any { it in CSV_SPECIALS }) {
        "\"" + defused.replace("\"", "\"\"") + "\""
    } else {
        defused
    }
}

private val FORMULA_TRIGGERS = charArrayOf('=', '+', '-', '@', '\t', '\r')
private val CSV_SPECIALS = charArrayOf(',', '"', '\n', '\r')
