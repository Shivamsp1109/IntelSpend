package com.spendwise.data.ingestion.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.spendwise.data.ingestion.ocr.OcrWord
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

sealed class PdfReadResult {
    /** The PDF had a usable text layer; [words] carry real page coordinates. */
    data class PositionalText(val words: List<OcrWord>, val text: String) : PdfReadResult()

    /** No text layer (a scan). Pages rendered for OCR. */
    data class ImageContent(val bitmaps: List<Bitmap>) : PdfReadResult()

    /** Encrypted, and the supplied password (if any) was wrong. */
    data class PasswordRequired(val wrongPassword: Boolean) : PdfReadResult()

    data class Failure(val message: String) : PdfReadResult()
}

class PdfReader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    init {
        PDFBoxResourceLoader.init(context)
    }

    suspend fun read(uri: Uri, password: String? = null): PdfReadResult = withContext(Dispatchers.IO) {
        purgeStaleTempFiles()

        val tempFile = File(context.cacheDir, "$TEMP_PREFIX${System.currentTimeMillis()}.pdf")
        var decryptedFile: File? = null

        try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied) return@withContext PdfReadResult.Failure("Could not open the file.")

            val document = try {
                PDDocument.load(tempFile, password ?: "")
            } catch (e: InvalidPasswordException) {
                return@withContext PdfReadResult.PasswordRequired(wrongPassword = !password.isNullOrEmpty())
            }

            val wasEncrypted = document.isEncrypted

            val words: List<OcrWord>
            val text: String
            try {
                val stripper = PositionalTextStripper()
                // Without this, PDFBox emits text in content-stream order, which for a
                // multi-column table is not reading order.
                stripper.sortByPosition = true
                text = stripper.getText(document)
                words = stripper.words
            } finally {
                if (wasEncrypted) {
                    // PdfRenderer cannot open an encrypted PDF at all, so if we end up needing
                    // the scan path we must hand it a decrypted copy.
                    decryptedFile = runCatching {
                        val out = File(context.cacheDir, "$TEMP_PREFIX${System.currentTimeMillis()}_dec.pdf")
                        document.isAllSecurityToBeRemoved = true
                        document.save(out)
                        out
                    }.getOrNull()
                }
                document.close()
            }

            if (words.size >= MIN_WORDS_FOR_TEXT_LAYER) {
                return@withContext PdfReadResult.PositionalText(words, text)
            }

            val renderSource = decryptedFile ?: tempFile
            val bitmaps = renderPages(renderSource)
            if (bitmaps.isEmpty()) {
                return@withContext PdfReadResult.Failure("This PDF has no readable text and its pages could not be rendered.")
            }
            PdfReadResult.ImageContent(bitmaps)
        } catch (e: Exception) {
            e.printStackTrace()
            PdfReadResult.Failure("Processing failed: ${e.message}")
        } finally {
            tempFile.delete()
            decryptedFile?.delete()
        }
    }

    private fun renderPages(file: File): List<Bitmap> {
        val bitmaps = mutableListOf<Bitmap>()
        // Closed explicitly rather than with `use`: PdfRenderer and PdfRenderer.Page only
        // declare AutoCloseable from API 33, so on this project's minSdk the interface dispatch
        // `use` compiles to would not resolve at runtime.
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                try {
                    val bitmap = Bitmap.createBitmap(
                        page.width * RENDER_SCALE,
                        page.height * RENDER_SCALE,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmaps.add(bitmap)
                } finally {
                    page.close()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
        return bitmaps
    }

    /**
     * A decrypted bank statement sitting in the cache is worse than the original, and the
     * process can die between writing and deleting one. Sweep on every read.
     */
    private fun purgeStaleTempFiles() {
        val cutoff = System.currentTimeMillis() - STALE_TEMP_AGE_MS
        context.cacheDir
            .listFiles { _, name -> name.startsWith(TEMP_PREFIX) }
            ?.forEach { file -> if (file.lastModified() < cutoff) file.delete() }
    }

    private companion object {
        const val TEMP_PREFIX = "temp_pdf_"
        const val RENDER_SCALE = 2
        const val STALE_TEMP_AGE_MS = 60_000L

        /**
         * Below this, whatever text layer exists is incidental (a scanner watermark, a
         * page-number stamp) and we are better off OCRing the rendered page.
         */
        const val MIN_WORDS_FOR_TEXT_LAYER = 15
    }
}

/**
 * Collects real word boxes from the PDF text layer.
 *
 * This replaces the previous approach of taking [PDFTextStripper]'s flat string and inventing
 * coordinates from character offsets. Character offsets do not preserve column alignment — a
 * short and a long description push their right-aligned amounts to completely different
 * offsets — which made every downstream column decision meaningless.
 */
private class PositionalTextStripper : PDFTextStripper() {
    val words = mutableListOf<OcrWord>()

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        super.writeString(text, textPositions)

        var buffer = StringBuilder()
        var left = 0f
        var right = 0f
        var top = 0f
        var bottom = 0f
        var open = false

        fun flush() {
            if (open && buffer.isNotBlank()) {
                words.add(
                    OcrWord(
                        text = buffer.toString(),
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        page = currentPageNo
                    )
                )
            }
            buffer = StringBuilder()
            open = false
        }

        for (position in textPositions) {
            val glyph = position.unicode
            if (glyph.isNullOrBlank()) {
                flush()
                continue
            }

            val glyphLeft = position.xDirAdj
            val glyphRight = glyphLeft + position.widthDirAdj
            val glyphBottom = position.yDirAdj
            val glyphTop = glyphBottom - position.heightDir

            if (open) {
                // Many PDFs separate words by positioning rather than by emitting a space
                // glyph, so a horizontal jump is itself a word boundary.
                val spaceWidth = position.widthOfSpace.takeIf { it > 0f } ?: position.widthDirAdj
                if (glyphLeft - right > spaceWidth * WORD_GAP_RATIO) flush()
            }

            if (!open) {
                left = glyphLeft
                top = glyphTop
                bottom = glyphBottom
                open = true
            } else {
                top = min(top, glyphTop)
                bottom = max(bottom, glyphBottom)
            }
            right = glyphRight
            buffer.append(glyph)
        }
        flush()
    }

    private companion object {
        const val WORD_GAP_RATIO = 0.3f
    }
}
