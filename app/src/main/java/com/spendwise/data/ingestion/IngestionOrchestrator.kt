package com.spendwise.data.ingestion

import android.content.Context
import android.net.Uri
import com.spendwise.data.ingestion.category.CategoryPredictor
import com.spendwise.data.ingestion.detector.FileType
import com.spendwise.data.ingestion.detector.FileTypeDetector
import com.spendwise.data.ingestion.duplicate.DuplicateChecker
import com.spendwise.data.ingestion.extractor.CsvExtractor
import com.spendwise.data.ingestion.extractor.ReceiptExtractor
import com.spendwise.data.ingestion.extractor.ScreenshotExtractor
import com.spendwise.data.ingestion.extractor.StatementExtractor
import com.spendwise.data.ingestion.geometry.RowGrouper
import com.spendwise.data.ingestion.llm.LlmTransactionMapper
import com.spendwise.data.ingestion.llm.MerchantEnricher
import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.data.ingestion.model.ReviewSeverity
import com.spendwise.data.ingestion.normalizer.AmountNormalizer
import com.spendwise.data.ingestion.normalizer.DateNormalizer
import com.spendwise.data.ingestion.ocr.OcrLine
import com.spendwise.data.ingestion.ocr.OcrProcessor
import com.spendwise.data.ingestion.ocr.OcrResult
import com.spendwise.data.ingestion.ocr.OcrWord
import com.spendwise.data.ingestion.reader.CsvReader
import com.spendwise.data.ingestion.reader.ImageReader
import com.spendwise.data.ingestion.reader.PdfReadResult
import com.spendwise.data.ingestion.reader.PdfReader
import com.spendwise.data.ingestion.reader.XlsxReadResult
import com.spendwise.data.ingestion.reader.XlsxReader
import com.spendwise.data.remote.ExtractionDataSource
import com.spendwise.data.remote.ExtractionResult
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.ExpenseSource
import com.spendwise.util.SmartExtractionPreferenceStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class IngestionResult {
    data class Success(
        val transactions: List<RawTransaction>,
        /**
         * Set when the results are degraded but still usable — most often smart
         * extraction was expected to run and didn't. Without this, a failed
         * model call is indistinguishable from a successful local parse, and
         * the user has no way to tell why the numbers look wrong.
         */
        val warning: String? = null
    ) : IngestionResult()
    data class Error(val message: String) : IngestionResult()
    /** The file is encrypted; ask the user for the password and call again. */
    data class PasswordRequired(val wrongPassword: Boolean) : IngestionResult()
}

class IngestionOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val csvReader: CsvReader,
    private val xlsxReader: XlsxReader,
    private val pdfReader: PdfReader,
    private val imageReader: ImageReader,
    private val ocrProcessor: OcrProcessor,
    private val receiptExtractor: ReceiptExtractor,
    private val screenshotExtractor: ScreenshotExtractor,
    private val statementExtractor: StatementExtractor,
    private val csvExtractor: CsvExtractor,
    private val categoryPredictor: CategoryPredictor,
    private val duplicateChecker: DuplicateChecker,
    private val extractionDataSource: ExtractionDataSource,
    private val llmTransactionMapper: LlmTransactionMapper,
    private val merchantEnricher: MerchantEnricher,
    private val smartExtractionPreferences: SmartExtractionPreferenceStore
) {
    suspend fun process(
        uri: Uri,
        fileName: String,
        sourceType: String = "image",
        password: String? = null
    ): IngestionResult = withContext(Dispatchers.IO) {
        val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val fileType = FileTypeDetector.detect(fileName, mimeType)
            ?: return@withContext IngestionResult.Error("Unsupported file type.")

        val extracted = try {
            when (fileType) {
                FileType.CSV -> readCsv(uri)
                FileType.XLSX -> readXlsx(uri)
                FileType.IMAGE -> readImage(uri, sourceType)
                FileType.PDF -> readPdf(uri, password)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext IngestionResult.Error("Processing failed: ${e.message}")
        }

        when (extracted) {
            is Extraction.Failed -> return@withContext IngestionResult.Error(extracted.message)
            is Extraction.NeedsPassword ->
                return@withContext IngestionResult.PasswordRequired(extracted.wrongPassword)
            is Extraction.Done -> Unit
        }

        val done = extracted as Extraction.Done
        val transactions = done.transactions
        if (transactions.isEmpty()) {
            return@withContext IngestionResult.Error(
                done.warning ?: "No transactions could be extracted from this file."
            )
        }

        // Runs before category prediction so the predictor sees the real brand
        // ("Amazon") rather than the bank's truncation ("Amazon I").
        val named = if (done.enrichMerchants && smartExtractionPreferences.enabled.value) {
            merchantEnricher.enrich(transactions)
        } else {
            transactions
        }

        val categorised = named.map { tx ->
            val predicted = tx.merchant?.let { categoryPredictor.predict(it) } ?: tx.category
            // Never let a prediction of Other overwrite a category the file stated outright.
            tx.copy(category = if (predicted == ExpenseCategory.Other) tx.category else predicted)
        }

        duplicateChecker.flagDuplicates(categorised)

        for (tx in categorised) {
            if (tx.fieldConfidence.severity() == ReviewSeverity.RED) tx.isSelected = false
        }

        IngestionResult.Success(categorised, warning = done.warning)
    }

    private sealed class Extraction {
        data class Done(
            val transactions: List<RawTransaction>,
            val warning: String? = null,
            /**
             * True for deterministic parses, whose payees come from bank narrations
             * and are truncated at source. False when a model already produced the
             * merchant, so it isn't sent back for a second opinion.
             */
            val enrichMerchants: Boolean = false
        ) : Extraction()
        data class Failed(val message: String) : Extraction()
        data class NeedsPassword(val wrongPassword: Boolean) : Extraction()
    }

    private fun readCsv(uri: Uri): Extraction {
        val rows = context.contentResolver.openInputStream(uri)?.use { csvReader.read(it) }
            ?: return Extraction.Failed("Could not open the file.")
        if (rows.isEmpty()) return Extraction.Failed("The file is empty.")
        return Extraction.Done(csvExtractor.extract(rows, ExpenseSource.CSV), enrichMerchants = true)
    }

    private fun readXlsx(uri: Uri): Extraction {
        val result = context.contentResolver.openInputStream(uri)?.use { xlsxReader.read(it) }
            ?: return Extraction.Failed("Could not open the file.")
        return when (result) {
            is XlsxReadResult.Rows -> Extraction.Done(
                csvExtractor.extract(result.rows, ExpenseSource.EXCEL),
                enrichMerchants = true
            )
            XlsxReadResult.PasswordProtected -> Extraction.Failed(
                "This workbook is password protected. Open it in Excel, remove the password, and try again."
            )
            XlsxReadResult.LegacyFormat -> Extraction.Failed(
                "This is an older .xls file. Open it and re-save as .xlsx or CSV, then try again."
            )
            is XlsxReadResult.Failure -> Extraction.Failed(result.message)
        }
    }

    private suspend fun readImage(uri: Uri, sourceType: String): Extraction {
        val bitmap = imageReader.read(uri) ?: return Extraction.Failed("Could not read the image.")
        val ocrResult = ocrProcessor.recognize(bitmap)
        if (ocrResult.fullText.isBlank() && !smartExtractionPreferences.enabled.value) {
            return Extraction.Failed("No text could be read from this image.")
        }

        val local = when (sourceType.lowercase()) {
            "screenshot" -> screenshotExtractor.extract(ocrResult)
            "scan" -> receiptExtractor.extract(ocrResult)
            else -> when (classify(ocrResult)) {
                DocumentKind.STATEMENT -> statementExtractor.extract(ocrResult)
                DocumentKind.RECEIPT -> receiptExtractor.extract(ocrResult)
                DocumentKind.SINGLE_PAYMENT -> screenshotExtractor.extract(ocrResult)
                    .ifEmpty { receiptExtractor.extract(ocrResult) }
            }
        }

        if (!smartExtractionPreferences.enabled.value) return Extraction.Done(local)

        val source = if (sourceType.equals("screenshot", ignoreCase = true)) {
            ExpenseSource.SCREENSHOT
        } else {
            ExpenseSource.OCR
        }

        return when (val remote = extractionDataSource.extract(bitmap, hint = sourceType)) {
            is ExtractionResult.Success -> {
                val mapped = llmTransactionMapper.map(remote.transactions, ocrResult.fullText, source)
                // Only prefer the model's answer if it actually produced something;
                // an empty remote result should not erase a usable local parse.
                if (mapped.isNotEmpty()) {
                    Extraction.Done(mapped)
                } else {
                    fallbackTo(local, "Smart extraction found no transactions in this image.")
                }
            }
            // Quota and network failures are not user errors — fall back to whatever
            // the on-device parse managed, but say so. Silently returning the local
            // result makes a broken backend look like a bad parse.
            is ExtractionResult.QuotaExceeded -> fallbackTo(local, remote.message)
            is ExtractionResult.Failed -> fallbackTo(local, remote.message)
        }
    }

    private fun fallbackTo(local: List<RawTransaction>, message: String): Extraction =
        if (local.isNotEmpty()) {
            Extraction.Done(
                local,
                warning = "$message Showing on-device results instead.",
                enrichMerchants = true
            )
        } else {
            Extraction.Failed(message)
        }

    private suspend fun readPdf(uri: Uri, password: String?): Extraction {
        return when (val result = pdfReader.read(uri, password)) {
            is PdfReadResult.PasswordRequired -> Extraction.NeedsPassword(result.wrongPassword)
            is PdfReadResult.Failure -> Extraction.Failed(result.message)

            is PdfReadResult.PositionalText -> {
                val ocrResult = OcrResult(
                    fullText = result.text,
                    lines = synthesizeLines(result.words),
                    words = result.words
                )
                Extraction.Done(extractByKind(ocrResult), enrichMerchants = true)
            }

            is PdfReadResult.ImageContent -> {
                val words = mutableListOf<OcrWord>()
                val lines = mutableListOf<OcrLine>()
                val text = StringBuilder()
                // Page index travels with each word, so the row grouper keeps pages apart
                // without the old trick of offsetting y by the previous page's height.
                result.bitmaps.forEachIndexed { index, bitmap ->
                    val page = ocrProcessor.recognize(bitmap, page = index)
                    words.addAll(page.words)
                    lines.addAll(page.lines)
                    text.append(page.fullText).append('\n')
                }
                if (words.isEmpty() && lines.isEmpty()) {
                    return Extraction.Failed("No text could be read from this PDF.")
                }
                Extraction.Done(extractByKind(OcrResult(text.toString(), lines, words)), enrichMerchants = true)
            }
        }
    }

    private fun extractByKind(ocrResult: OcrResult): List<RawTransaction> =
        when (classify(ocrResult)) {
            DocumentKind.STATEMENT -> statementExtractor.extract(ocrResult)
            DocumentKind.RECEIPT -> receiptExtractor.extract(ocrResult)
            DocumentKind.SINGLE_PAYMENT -> receiptExtractor.extract(ocrResult)
        }

    /** OcrLine is still what the merchant scorer reads, so derive it from the word rows. */
    private fun synthesizeLines(words: List<OcrWord>): List<OcrLine> =
        RowGrouper.group(words).map { row ->
            OcrLine(
                text = row.text,
                left = row.left.toInt(),
                top = row.top.toInt(),
                right = row.right.toInt(),
                bottom = row.bottom.toInt(),
                height = row.height.toInt().coerceAtLeast(1)
            )
        }

    private enum class DocumentKind { STATEMENT, RECEIPT, SINGLE_PAYMENT }

    /**
     * Classifies by structure first, keywords second.
     *
     * Counting rows that carry both a date and a money figure is a far better statement
     * detector than a keyword list: a table of transactions has many such rows, and a receipt
     * or a payment confirmation has one or two regardless of the vocabulary it uses.
     */
    private fun classify(ocrResult: OcrResult): DocumentKind {
        val rows = RowGrouper.group(ocrResult.wordsOrApproximate())
        val tabular = rows.count { row ->
            DateNormalizer.findFirstToken(row.text) != null &&
                AmountNormalizer.extractAmountMatches(row.text, allowInteger = false).isNotEmpty()
        }
        if (tabular >= MIN_TABULAR_ROWS) return DocumentKind.STATEMENT

        val lower = ocrResult.fullText.lowercase()
        val receiptScore = RECEIPT_SIGNALS.count { lower.contains(it) }
        val statementScore = STATEMENT_SIGNALS.count { lower.contains(it) }

        return when {
            statementScore > receiptScore && tabular >= 2 -> DocumentKind.STATEMENT
            receiptScore >= 2 -> DocumentKind.RECEIPT
            else -> DocumentKind.SINGLE_PAYMENT
        }
    }

    private companion object {
        const val MIN_TABULAR_ROWS = 4
        val RECEIPT_SIGNALS = listOf(
            "invoice", "bill no", "gstin", "total:", "amount payable", "net amount",
            "taxable", "qty", "item name", "hsn"
        )
        val STATEMENT_SIGNALS = listOf(
            "balance", "withdrawal", "deposit", "transaction id", "upi/", "debit", "credit",
            "statement", "ifsc", "account number"
        )
    }
}
