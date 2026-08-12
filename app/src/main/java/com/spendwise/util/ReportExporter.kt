package com.spendwise.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseReport
import com.spendwise.domain.model.ReportFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.OutputStreamWriter
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a period's report to a user-chosen file.
 *
 * The caller supplies a [Uri] from the system file picker, so the app never
 * needs storage permissions and the user decides where their financial data
 * lands.
 */
sealed class SaveResult {
    /** [path] is where the file landed, for showing back to the user. */
    data class Saved(val path: String) : SaveResult()
    data class Failed(val message: String) : SaveResult()
}

@Singleton
class ReportExporter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun write(uri: Uri, report: ExpenseReport, format: ReportFormat) = when (format) {
        ReportFormat.CSV -> writeCsv(uri, report)
        ReportFormat.PDF -> writePdf(uri, report)
    }

    /**
     * Saves the report into Downloads/IntelSpend and returns where it landed.
     *
     * Two routes, because the platform changed underneath this. From Android 10
     * the MediaStore owns shared storage and an app can add to Downloads with no
     * permission at all; before that, the folder is a plain directory and needs
     * WRITE_EXTERNAL_STORAGE. The older path is the one that can fail, so it
     * reports a clear reason rather than an IO exception.
     */
    fun save(report: ExpenseReport, format: ReportFormat): SaveResult {
        val fileName = "${report.fileNameStem}.${format.extension}"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(fileName, report, format)
        } else {
            saveToPublicDirectory(fileName, report, format)
        }
    }

    /** True when [save] needs a runtime permission on this device. */
    fun needsStoragePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** MediaStore's Downloads collection only exists from Android 10; [save] gates the call. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        fileName: String,
        report: ExpenseReport,
        format: ReportFormat
    ): SaveResult {
        val details = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, format.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            // Hides the row until the bytes are there, so a file manager can't
            // open a half-written report.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, details)
            ?: return SaveResult.Failed("Could not create the file in Downloads.")

        return try {
            write(uri, report, format)
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )
            SaveResult.Saved(displayPath(fileName))
        } catch (e: Exception) {
            // A pending row nobody can see is worse than no row, so clear it.
            runCatching { resolver.delete(uri, null, null) }
            Log.w(TAG, "Saving $fileName via MediaStore failed.", e)
            SaveResult.Failed("Could not save the report.")
        }
    }

    @Suppress("DEPRECATION") // The public directory is the only route before Android 10.
    private fun saveToPublicDirectory(
        fileName: String,
        report: ExpenseReport,
        format: ReportFormat
    ): SaveResult {
        val folder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FOLDER
        )
        if (!folder.exists() && !folder.mkdirs()) {
            return SaveResult.Failed("Could not create the IntelSpend folder.")
        }

        val target = uniqueFile(folder, fileName)
        return try {
            write(Uri.fromFile(target), report, format)
            SaveResult.Saved(displayPath(target.name))
        } catch (e: Exception) {
            Log.w(TAG, "Saving ${target.name} to Downloads failed.", e)
            SaveResult.Failed("Could not save the report.")
        }
    }

    /**
     * MediaStore appends "(1)" to a clashing name by itself; the legacy path
     * would silently overwrite last month's report, so it does the same here.
     */
    private fun uniqueFile(folder: File, fileName: String): File {
        val stem = fileName.substringBeforeLast('.')
        val extension = fileName.substringAfterLast('.')
        var candidate = File(folder, fileName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(folder, "$stem ($index).$extension")
            index++
        }
        return candidate
    }

    private fun displayPath(fileName: String) =
        "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$fileName"

    /**
     * Charset is explicit because the platform default is not UTF-8 everywhere,
     * and the previous export mangled every ₹ it wrote. The byte-order mark is
     * there for Excel, which otherwise reads a UTF-8 CSV as the local codepage
     * and shows the same mojibake.
     */
    private fun writeCsv(uri: Uri, report: ExpenseReport) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                writer.write(BOM_CODE_POINT)
                writer.appendLine(CSV_HEADER)
                report.transactions.forEach { expense ->
                    writer.appendLine(expense.toCsvRow())
                }
            }
        } ?: error("Could not open $uri for writing")
    }

    private fun Expense.toCsvRow(): String = csvRow(this, isoDate)

    private fun writePdf(uri: Uri, report: ExpenseReport) {
        val document = PdfDocument()
        try {
            val writer = PdfWriter(document, report)
            writer.drawHeader()
            writer.drawSummary()
            writer.drawCategoryBreakdown()
            writer.drawTransactions()
            writer.finish()

            context.contentResolver.openOutputStream(uri)?.use { document.writeTo(it) }
                ?: error("Could not open $uri for writing")
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

        /** Subfolder of the device's Downloads directory. */
        const val FOLDER = "IntelSpend"

        private const val TAG = "ReportExporter"

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
