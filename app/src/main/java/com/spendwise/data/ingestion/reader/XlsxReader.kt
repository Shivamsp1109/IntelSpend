package com.spendwise.data.ingestion.reader

import com.spendwise.data.ingestion.normalizer.DateNormalizer
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.xml.parsers.SAXParserFactory

sealed class XlsxReadResult {
    data class Rows(val rows: List<List<String>>) : XlsxReadResult()
    /** ECMA-376 encrypted workbook — an OLE2 container, not a zip. */
    object PasswordProtected : XlsxReadResult()
    /** Pre-2007 binary .xls, which is a different format entirely. */
    object LegacyFormat : XlsxReadResult()
    data class Failure(val message: String) : XlsxReadResult()
}

/**
 * Reads .xlsx into rows of strings without Apache POI.
 *
 * An .xlsx is a zip of XML, so a streaming reader over the two parts that matter — the sheet
 * and the shared string table — costs a couple hundred lines and no dependency weight. POI on
 * Android drags in javax.xml and a large method count for the same result.
 *
 * Uses SAX rather than Android's XmlPullParser so the same code runs under JVM unit tests.
 */
class XlsxReader @Inject constructor() {

    fun read(inputStream: InputStream): XlsxReadResult {
        val bytes = inputStream.readBytes()
        if (bytes.size < 4) return XlsxReadResult.Failure("The file is empty.")

        // Both an encrypted .xlsx and a legacy binary .xls are OLE2 compound files, so the
        // magic number alone can't tell them apart. Encrypted OOXML carries an
        // "EncryptedPackage" stream; look for its UTF-16LE name.
        if (bytes.startsWithBytes(0xD0, 0xCF, 0x11, 0xE0)) {
            return if (containsUtf16(bytes, "EncryptedPackage")) {
                XlsxReadResult.PasswordProtected
            } else {
                XlsxReadResult.LegacyFormat
            }
        }
        if (!bytes.startsWithBytes(0x50, 0x4B)) {
            return XlsxReadResult.Failure("This does not look like an Excel workbook.")
        }

        val parts = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (name == SHARED_STRINGS || name == STYLES || name == WORKBOOK ||
                        name.startsWith(WORKSHEET_PREFIX)
                    ) {
                        parts[name] = zip.readBytes()
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            return XlsxReadResult.Failure("The workbook could not be opened: ${e.message}")
        }

        val sheetName = pickSheet(parts.keys)
            ?: return XlsxReadResult.Failure("The workbook has no readable sheet.")

        val sharedStrings = parts[SHARED_STRINGS]?.let { parseSharedStrings(it) } ?: emptyList()
        val dateStyles = parts[STYLES]?.let { parseDateStyles(it) } ?: emptySet()

        return try {
            val rows = parseSheet(parts.getValue(sheetName), sharedStrings, dateStyles)
            XlsxReadResult.Rows(rows.filter { row -> row.any { it.isNotBlank() } })
        } catch (e: Exception) {
            XlsxReadResult.Failure("The sheet could not be parsed: ${e.message}")
        }
    }

    /** Lowest-numbered worksheet, which is the leftmost tab in practice. */
    private fun pickSheet(names: Set<String>): String? =
        names.filter { it.startsWith(WORKSHEET_PREFIX) && it.endsWith(".xml") }
            .minByOrNull { name ->
                Regex("(\\d+)").find(name.removePrefix(WORKSHEET_PREFIX))
                    ?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
            }

    private fun parseSharedStrings(data: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val current = StringBuilder()
        var inItem = false
        var inText = false

        parse(data, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes?) {
                when (localName(qName)) {
                    "si" -> {
                        inItem = true
                        current.setLength(0)
                    }
                    // A shared string can be split into several runs; concatenate them all.
                    "t" -> inText = inItem
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) current.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, local: String?, qName: String) {
                when (localName(qName)) {
                    "t" -> inText = false
                    "si" -> {
                        strings.add(current.toString())
                        inItem = false
                    }
                }
            }
        })
        return strings
    }

    /**
     * Collects the cell-format indices that represent dates.
     *
     * Without this a date cell and an amount cell are both just a number, and "45678" would
     * import as forty-five thousand rupees instead of a date.
     */
    private fun parseDateStyles(data: ByteArray): Set<Int> {
        val dateFormatIds = BUILTIN_DATE_FORMATS.toMutableSet()
        val cellFormats = mutableListOf<Int>()
        var inCellXfs = false

        parse(data, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes?) {
                when (localName(qName)) {
                    "numFmt" -> {
                        val id = attrs?.getValue("numFmtId")?.toIntOrNull()
                        val code = attrs?.getValue("formatCode").orEmpty()
                        // Strip literals and colour/condition sections before sniffing for
                        // date tokens, so a currency format like [$-409]#,##0.00 isn't caught.
                        val stripped = code.replace(Regex("\\[[^\\]]*\\]"), "")
                            .replace(Regex("\"[^\"]*\""), "")
                        if (id != null && DATE_TOKENS.containsMatchIn(stripped)) dateFormatIds.add(id)
                    }
                    "cellXfs" -> inCellXfs = true
                    "xf" -> if (inCellXfs) {
                        cellFormats.add(attrs?.getValue("numFmtId")?.toIntOrNull() ?: 0)
                    }
                }
            }

            override fun endElement(uri: String?, local: String?, qName: String) {
                if (localName(qName) == "cellXfs") inCellXfs = false
            }
        })

        return cellFormats.withIndex()
            .filter { (_, numFmtId) -> numFmtId in dateFormatIds }
            .map { it.index }
            .toSet()
    }

    private fun parseSheet(
        data: ByteArray,
        sharedStrings: List<String>,
        dateStyles: Set<Int>
    ): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var cells = mutableMapOf<Int, String>()
        var columnIndex = 0
        var cellType: String? = null
        var styleIndex: Int? = null
        val value = StringBuilder()
        var capturing = false

        parse(data, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes?) {
                when (localName(qName)) {
                    "row" -> cells = mutableMapOf()
                    "c" -> {
                        // Excel omits empty cells, so the column must come from the cell
                        // reference. Reading sequentially would shift every later column.
                        columnIndex = columnOf(attrs?.getValue("r"))
                        cellType = attrs?.getValue("t")
                        styleIndex = attrs?.getValue("s")?.toIntOrNull()
                        value.setLength(0)
                    }
                    "v", "t" -> capturing = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capturing) value.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, local: String?, qName: String) {
                when (localName(qName)) {
                    "v", "t" -> capturing = false
                    "c" -> {
                        val resolved = resolveCell(
                            raw = value.toString(),
                            type = cellType,
                            styleIndex = styleIndex,
                            sharedStrings = sharedStrings,
                            dateStyles = dateStyles
                        )
                        if (resolved.isNotEmpty()) cells[columnIndex] = resolved
                    }
                    "row" -> {
                        val width = (cells.keys.maxOrNull() ?: -1) + 1
                        rows.add((0 until width).map { cells[it].orEmpty() })
                    }
                }
            }
        })
        return rows
    }

    private fun resolveCell(
        raw: String,
        type: String?,
        styleIndex: Int?,
        sharedStrings: List<String>,
        dateStyles: Set<Int>
    ): String {
        if (raw.isEmpty()) return ""
        return when (type) {
            "s" -> raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
            "inlineStr", "str" -> raw
            "b" -> if (raw == "1") "TRUE" else "FALSE"
            else -> {
                val numeric = raw.toDoubleOrNull() ?: return raw
                if (styleIndex != null && styleIndex in dateStyles) {
                    // Emit ISO so downstream date parsing is unambiguous.
                    DateNormalizer.excelSerialToMillis(numeric)?.let { ISO_DATE.get()!!.format(it) } ?: raw
                } else {
                    raw
                }
            }
        }
    }

    /** "BC12" -> 54. */
    private fun columnOf(ref: String?): Int {
        if (ref.isNullOrEmpty()) return 0
        var index = 0
        for (ch in ref) {
            if (!ch.isLetter()) break
            index = index * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return (index - 1).coerceAtLeast(0)
    }

    private fun parse(data: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        factory.newSAXParser().parse(InputSource(ByteArrayInputStream(data)), handler)
    }

    private fun localName(qName: String): String = qName.substringAfterLast(':')

    private fun ByteArray.startsWithBytes(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.withIndex().all { (i, b) -> this[i].toInt() and 0xFF == b }

    /** OLE2 stream names are stored UTF-16LE in the directory entries. */
    private fun containsUtf16(bytes: ByteArray, needle: String): Boolean {
        val pattern = needle.flatMap { listOf(it.code.toByte(), 0.toByte()) }.toByteArray()
        if (pattern.isEmpty() || bytes.size < pattern.size) return false
        outer@ for (i in 0..bytes.size - pattern.size) {
            for (j in pattern.indices) {
                if (bytes[i + j] != pattern[j]) continue@outer
            }
            return true
        }
        return false
    }

    private companion object {
        const val SHARED_STRINGS = "xl/sharedStrings.xml"
        const val STYLES = "xl/styles.xml"
        const val WORKBOOK = "xl/workbook.xml"
        const val WORKSHEET_PREFIX = "xl/worksheets/"

        /** Built-in numFmtIds that Excel reserves for date and time formats. */
        val BUILTIN_DATE_FORMATS = setOf(14, 15, 16, 17, 18, 19, 20, 21, 22, 45, 46, 47)
        val DATE_TOKENS = Regex("[yYdD]|m{3,}")

        val ISO_DATE: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
    }
}
