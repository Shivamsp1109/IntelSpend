package com.spendwise.data.ingestion.reader

import com.spendwise.data.ingestion.detector.FileType
import com.spendwise.data.ingestion.detector.FileTypeDetector
import com.spendwise.data.ingestion.normalizer.DateNormalizer
import com.spendwise.data.ingestion.normalizer.DayMonthOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale

class XlsxReaderTest {
    private val reader = XlsxReader()

    /** OLE2 container carrying an EncryptedPackage stream: a password-protected .xlsx. */
    @Test
    fun `reports an encrypted workbook rather than a parse error`() {
        val bytes = ole2(streamName = "EncryptedPackage")
        assertEquals(XlsxReadResult.PasswordProtected, reader.read(ByteArrayInputStream(bytes)))
    }

    /** The same magic number without that stream is a pre-2007 binary .xls. */
    @Test
    fun `distinguishes legacy xls from an encrypted xlsx`() {
        val bytes = ole2(streamName = "Workbook")
        assertEquals(XlsxReadResult.LegacyFormat, reader.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `rejects a file that is neither zip nor ole2`() {
        val result = reader.read(ByteArrayInputStream("Date,Amount\n01/01/2026,5".toByteArray()))
        assertTrue(result is XlsxReadResult.Failure)
    }

    private fun ole2(streamName: String): ByteArray {
        val header = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())
        val padding = ByteArray(64)
        val utf16Name = streamName.flatMap { listOf(it.code.toByte(), 0.toByte()) }.toByteArray()
        return header + padding + utf16Name + ByteArray(32)
    }
}

class FileTypeDetectorTest {
    @Test
    fun `detects spreadsheet and document extensions`() {
        assertEquals(FileType.XLSX, FileTypeDetector.detect("statement.xlsx"))
        assertEquals(FileType.XLSX, FileTypeDetector.detect("STATEMENT.XLS"))
        assertEquals(FileType.CSV, FileTypeDetector.detect("export.tsv"))
        assertEquals(FileType.PDF, FileTypeDetector.detect("jan.pdf"))
        assertEquals(FileType.IMAGE, FileTypeDetector.detect("receipt.HEIC"))
        assertNull(FileTypeDetector.detect("notes.docx"))
    }

    /** Content providers often hand back an opaque display name with no extension. */
    @Test
    fun `falls back to the mime type when the name has no extension`() {
        assertEquals(
            FileType.XLSX,
            FileTypeDetector.detect(
                "document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        )
        assertEquals(FileType.PDF, FileTypeDetector.detect("content-1234", "application/pdf"))
        assertEquals(FileType.IMAGE, FileTypeDetector.detect("content-1234", "image/heic"))
    }
}

class CsvReaderSniffingTest {
    private val reader = CsvReader()

    @Test
    fun `keeps a quoted delimiter inside one field`() {
        val csv = "Date,Description,Amount\n12/01/2026,\"AMAZON PAY, MUMBAI\",1299.00\n"
        val rows = reader.read(ByteArrayInputStream(csv.toByteArray()))
        assertEquals(listOf("12/01/2026", "AMAZON PAY, MUMBAI", "1299.00"), rows[1])
    }

    @Test
    fun `picks the semicolon when commas are only decimal separators`() {
        val csv = "Datum;Text;Betrag\n12.01.2026;AMAZON EU;-1.299,00\n15.01.2026;LOHN;85.000,00\n"
        val rows = reader.read(ByteArrayInputStream(csv.toByteArray()))
        assertEquals(3, rows[0].size)
        assertEquals("-1.299,00", rows[1][2])
    }

    @Test
    fun `reads utf16 with a byte order mark`() {
        val csv = "Date,Description,Amount\n12/01/2026,AMAZON PAY,-1299.00\n"
        val rows = reader.read(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_16)))
        assertEquals("Date", rows[0][0])
        assertEquals("AMAZON PAY", rows[1][1])
    }

    @Test
    fun `reads tab separated values`() {
        val csv = "Date\tDescription\tAmount\n12/01/2026\tAMAZON PAY\t1299.00\n"
        val rows = reader.read(ByteArrayInputStream(csv.toByteArray()))
        assertEquals(3, rows[1].size)
        assertEquals("AMAZON PAY", rows[1][1])
    }
}

class DateNormalizerTest {
    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun format(millis: Long?) = millis?.let { iso.format(it) }

    @Test
    fun `resolves day-month order across the whole document`() {
        // 15 can only be a day, which settles the order for every other token in the file.
        val lines = listOf("15/01/2026 AMAZON", "05/01/2026 SWIGGY")
        val order = DateNormalizer.resolveOrder(lines)
        assertEquals(DayMonthOrder.DAY_FIRST, order)
        assertEquals("2026-01-05", format(DateNormalizer.normalize("05/01/2026", order)))
    }

    @Test
    fun `resolves month-first documents the other way`() {
        val lines = listOf("01/15/2026 AMAZON", "01/05/2026 STARBUCKS")
        val order = DateNormalizer.resolveOrder(lines)
        assertEquals(DayMonthOrder.MONTH_FIRST, order)
        assertEquals("2026-01-05", format(DateNormalizer.normalize("01/05/2026", order)))
    }

    @Test
    fun `flags a genuinely ambiguous token instead of guessing silently`() {
        val parsed = DateNormalizer.parse("03/05/2026", DayMonthOrder.UNKNOWN)
        assertTrue("expected the ambiguity to be reported", parsed!!.ambiguous)
    }

    @Test
    fun `a component above twelve disambiguates itself regardless of document order`() {
        assertEquals(
            "2026-01-25",
            format(DateNormalizer.normalize("25/01/2026", DayMonthOrder.MONTH_FIRST))
        )
    }

    @Test
    fun `rejects implausible dates instead of importing them`() {
        assertNull(DateNormalizer.normalize("31/12/2099"))
        assertNull(DateNormalizer.normalize("01/01/1971"))
        assertNull(DateNormalizer.normalize("32/01/2026"))
    }

    @Test
    fun `converts excel day-count serials`() {
        // 46034 is 2026-01-12 in Excel's 1900 date system.
        assertEquals("2026-01-12", format(DateNormalizer.fromExcelSerial(46034.0)))
    }
}
