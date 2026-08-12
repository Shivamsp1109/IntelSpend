package com.spendwise.data.ingestion.ocr

/**
 * A single word with its real bounding box on the page.
 *
 * This is the unit the geometric extractors work on. Both source paths produce it:
 * ML Kit hands us [com.google.mlkit.vision.text.Text.Element] boxes, and the PDF text
 * layer hands us glyph positions via PDFBox. Keeping words (rather than collapsing to
 * line strings) is what lets us answer "which number sits under the Debit header" and
 * "which number shares a row with the word TOTAL".
 */
data class OcrWord(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val page: Int = 0
) {
    val centerY: Float get() = (top + bottom) / 2f
    val centerX: Float get() = (left + right) / 2f
    val height: Float get() = bottom - top
    val width: Float get() = right - left
}

data class OcrLine(
    val text: String,
    val left: Int = 0,
    val top: Int,
    val right: Int = 0,
    val height: Int,
    val bottom: Int = top + height
)

data class OcrResult(
    val fullText: String,
    val lines: List<OcrLine>,
    val words: List<OcrWord> = emptyList()
) {
    /**
     * Words if the source gave us real ones, otherwise an approximation spread across each
     * line's box. The approximation is only good enough for row grouping (one line = one row);
     * column work should check [hasRealGeometry] first.
     *
     * Degrades all the way down to plain [fullText] so a caller that only has text still gets
     * usable rows instead of nothing.
     */
    fun wordsOrApproximate(): List<OcrWord> = when {
        words.isNotEmpty() -> words
        lines.isNotEmpty() -> lines.flatMap { approximateWords(it) }
        else -> linesFromText().flatMap { approximateWords(it) }
    }

    val hasRealGeometry: Boolean get() = words.isNotEmpty()

    /** Lines if we have them, otherwise split from [fullText]. */
    fun linesOrFromText(): List<OcrLine> = lines.ifEmpty { linesFromText() }

    private fun linesFromText(): List<OcrLine> =
        fullText.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { index, text ->
                OcrLine(
                    text = text,
                    left = 0,
                    top = index * SYNTHETIC_LINE_PITCH,
                    right = text.length * SYNTHETIC_CHAR_WIDTH,
                    height = SYNTHETIC_LINE_HEIGHT
                )
            }

    private fun approximateWords(line: OcrLine): List<OcrWord> {
        val tokens = Regex("\\S+").findAll(line.text).toList()
        if (tokens.isEmpty()) return emptyList()
        val span = (line.right - line.left).toFloat().takeIf { it > 0f }
            ?: (line.text.length * 8f)
        val charWidth = span / line.text.length.coerceAtLeast(1)
        return tokens.map { token ->
            OcrWord(
                text = token.value,
                left = line.left + charWidth * token.range.first,
                top = line.top.toFloat(),
                right = line.left + charWidth * (token.range.last + 1),
                bottom = line.bottom.toFloat()
            )
        }
    }

    private companion object {
        const val SYNTHETIC_LINE_PITCH = 24
        const val SYNTHETIC_LINE_HEIGHT = 20
        const val SYNTHETIC_CHAR_WIDTH = 8
    }
}
