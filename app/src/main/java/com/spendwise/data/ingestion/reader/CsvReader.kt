package com.spendwise.data.ingestion.reader

import com.opencsv.CSVParserBuilder
import com.opencsv.CSVReaderBuilder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import javax.inject.Inject

/**
 * Reads a delimited text file into rows, sniffing both the character encoding and the
 * delimiter rather than assuming UTF-8 and comma.
 *
 * Bank "download as CSV" exports are routinely semicolon- or tab-separated, and the ones
 * produced for Excel are often UTF-16LE or Windows-1252. Assuming comma/UTF-8 turns those
 * into a single garbled column, which reads downstream as "no transactions found".
 */
class CsvReader @Inject constructor() {

    fun read(inputStream: InputStream): List<List<String>> {
        val bytes = inputStream.readBytes()
        if (bytes.isEmpty()) return emptyList()

        val (charset, bodyOffset) = detectCharset(bytes)
        val sample = String(bytes, bodyOffset, bytes.size - bodyOffset, charset)
        val delimiter = detectDelimiter(sample)

        val parser = CSVParserBuilder()
            .withSeparator(delimiter)
            .withIgnoreQuotations(false)
            .build()

        return ByteArrayInputStream(bytes, bodyOffset, bytes.size - bodyOffset).use { stream ->
            InputStreamReader(stream, charset).use { reader ->
                CSVReaderBuilder(reader).withCSVParser(parser).build().use { csv ->
                    csv.readAll()
                        // detectCharset already skipped the BOM bytes, but a stray U+FEFF can
                        // still surface if a cell itself opens with one; strip it defensively.
                        // Written as an escape, not a literal char, since a literal BOM in the
                        // source file is itself a lint error (ByteOrderMark).
                        .map { row -> row.map { it.trim().removePrefix("\uFEFF") } }
                        .filter { row -> row.any { it.isNotBlank() } }
                }
            }
        }
    }

    /** Returns the charset plus the number of leading BOM bytes to skip. */
    private fun detectCharset(bytes: ByteArray): Pair<Charset, Int> {
        fun startsWith(vararg prefix: Int): Boolean =
            bytes.size >= prefix.size && prefix.withIndex().all { (i, b) ->
                bytes[i].toInt() and 0xFF == b
            }

        return when {
            startsWith(0xEF, 0xBB, 0xBF) -> StandardCharsets.UTF_8 to 3
            startsWith(0xFF, 0xFE) -> StandardCharsets.UTF_16LE to 2
            startsWith(0xFE, 0xFF) -> StandardCharsets.UTF_16BE to 2
            // No BOM. UTF-16 without one shows up as text riddled with NUL bytes.
            looksLikeUtf16(bytes, oddOffset = true) -> StandardCharsets.UTF_16LE to 0
            looksLikeUtf16(bytes, oddOffset = false) -> StandardCharsets.UTF_16BE to 0
            isValidUtf8(bytes) -> StandardCharsets.UTF_8 to 0
            else -> WINDOWS_1252 to 0
        }
    }

    private fun looksLikeUtf16(bytes: ByteArray, oddOffset: Boolean): Boolean {
        val window = bytes.take(512)
        if (window.size < 8) return false
        val start = if (oddOffset) 1 else 0
        var nulls = 0
        var count = 0
        for (i in start until window.size step 2) {
            count++
            if (window[i] == 0.toByte()) nulls++
        }
        return count > 0 && nulls.toFloat() / count > 0.6f
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        val decoder = StandardCharsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes.take(4096).toByteArray()))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Picks the delimiter whose per-line field count is both high and consistent. Consistency
     * matters more than raw frequency: commas inside descriptions ("AMAZON, MUMBAI") are
     * common, but they don't produce the same count on every line the way a real delimiter does.
     */
    private fun detectDelimiter(sample: String): Char {
        val lines = sample.lineSequence()
            .filter { it.isNotBlank() }
            .take(30)
            .toList()
        if (lines.isEmpty()) return ','

        return CANDIDATE_DELIMITERS
            .map { delimiter ->
                val counts = lines.map { line -> countOutsideQuotes(line, delimiter) }
                val nonZero = counts.filter { it > 0 }
                val mode = nonZero.groupingBy { it }.eachCount().maxByOrNull { it.value }
                val agreement = if (nonZero.isEmpty()) 0f else (mode?.value ?: 0).toFloat() / lines.size
                val fields = mode?.key ?: 0
                delimiter to (agreement * 10f + fields.coerceAtMost(20))
            }
            .maxByOrNull { it.second }
            ?.first
            ?: ','
    }

    private fun countOutsideQuotes(line: String, delimiter: Char): Int {
        var inQuotes = false
        var count = 0
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> count++
            }
        }
        return count
    }

    private companion object {
        val CANDIDATE_DELIMITERS = listOf(',', ';', '\t', '|')
        val WINDOWS_1252: Charset = runCatching { Charset.forName("windows-1252") }
            .getOrDefault(StandardCharsets.ISO_8859_1)
    }
}
