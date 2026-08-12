package com.spendwise.data.ingestion.geometry

import com.spendwise.data.ingestion.ocr.OcrWord
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A visual row of text: every word whose vertical span lines up, ordered left to right.
 *
 * Tables are the reason this exists. A statement row is only meaningful as a row — the date
 * belongs to the description belongs to the amount in the Debit column. Neither ML Kit nor
 * PDFBox hands us rows, so we reconstruct them from word boxes.
 */
data class TextRow(
    val words: List<OcrWord>,
    val page: Int
) {
    val text: String by lazy { words.joinToString(" ") { it.text } }
    val top: Float get() = words.minOf { it.top }
    val bottom: Float get() = words.maxOf { it.bottom }
    val left: Float get() = words.minOf { it.left }
    val right: Float get() = words.maxOf { it.right }
    val centerY: Float get() = (top + bottom) / 2f
    val height: Float get() = bottom - top

    /** Words whose horizontal centre falls inside [xStart, xEnd]. */
    fun wordsWithin(xStart: Float, xEnd: Float): List<OcrWord> =
        words.filter { it.centerX >= xStart && it.centerX <= xEnd }

    /** Text of the row up to (but not including) [x] — usually the description side. */
    fun textLeftOf(x: Float): String =
        words.filter { it.right <= x }.joinToString(" ") { it.text }

    /**
     * Largest horizontal gap between consecutive words, as a multiple of median char width.
     * A big gap is the signature of a column boundary rather than a word space.
     */
    fun largestGap(): Float {
        if (words.size < 2) return 0f
        return (1 until words.size).maxOf { words[it].left - words[it - 1].right }
    }
}

object RowGrouper {

    /**
     * Groups words into visual rows.
     *
     * [toleranceRatio] is a fraction of the median word height; two words land in the same row
     * when their vertical centres are closer than that. 0.6 tolerates the baseline jitter OCR
     * produces on a scanned page without merging genuinely adjacent rows.
     */
    fun group(words: List<OcrWord>, toleranceRatio: Float = 0.6f): List<TextRow> {
        if (words.isEmpty()) return emptyList()

        return words
            .groupBy { it.page }
            .toSortedMap()
            .flatMap { (page, pageWords) -> groupPage(pageWords, page, toleranceRatio) }
    }

    private fun groupPage(words: List<OcrWord>, page: Int, toleranceRatio: Float): List<TextRow> {
        val heights = words.map { it.height }.filter { it > 0f }.sorted()
        val medianHeight = if (heights.isEmpty()) 12f else heights[heights.size / 2]
        val tolerance = max(1f, medianHeight * toleranceRatio)

        val sorted = words.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<OcrWord>>()
        var current = mutableListOf<OcrWord>()
        var currentCenter = sorted.first().centerY

        for (word in sorted) {
            val sameRow = current.isEmpty() ||
                abs(word.centerY - currentCenter) <= tolerance ||
                verticalOverlapRatio(word, current) >= 0.5f
            if (sameRow) {
                current.add(word)
                // Running mean keeps the row anchored as it grows, so a slowly drifting
                // baseline across a wide table doesn't split the row in the middle.
                currentCenter = current.map { it.centerY }.average().toFloat()
            } else {
                rows.add(current)
                current = mutableListOf(word)
                currentCenter = word.centerY
            }
        }
        if (current.isNotEmpty()) rows.add(current)

        return rows
            .map { TextRow(it.sortedBy { w -> w.left }, page) }
            .sortedBy { it.top }
    }

    private fun verticalOverlapRatio(word: OcrWord, row: List<OcrWord>): Float {
        val rowTop = row.minOf { it.top }
        val rowBottom = row.maxOf { it.bottom }
        val overlap = min(word.bottom, rowBottom) - max(word.top, rowTop)
        if (overlap <= 0f) return 0f
        val smaller = min(word.height, rowBottom - rowTop).takeIf { it > 0f } ?: return 0f
        return overlap / smaller
    }
}

object Clustering {
    /**
     * Single-linkage 1-D clustering. Used to find table columns from the x positions of
     * numbers: amounts in a financial table are right-aligned, so clustering their right
     * edges recovers the column boundaries without needing a header row at all.
     */
    fun cluster(values: List<Float>, tolerance: Float): List<List<Float>> {
        if (values.isEmpty()) return emptyList()
        val sorted = values.sorted()
        val clusters = mutableListOf<MutableList<Float>>()
        var current = mutableListOf(sorted.first())

        for (value in sorted.drop(1)) {
            if (value - current.last() <= tolerance) {
                current.add(value)
            } else {
                clusters.add(current)
                current = mutableListOf(value)
            }
        }
        clusters.add(current)
        return clusters
    }
}
