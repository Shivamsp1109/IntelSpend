package com.spendwise.data.ingestion.detector

enum class FileType { PDF, CSV, IMAGE, XLSX }

object FileTypeDetector {
    fun detect(fileName: String, mimeType: String? = null): FileType? {
        val name = fileName.lowercase()
        byExtension(name)?.let { return it }
        return mimeType?.let { byMimeType(it.lowercase()) }
    }

    private fun byExtension(name: String): FileType? = when {
        name.endsWith(".pdf") -> FileType.PDF
        name.endsWith(".csv") || name.endsWith(".tsv") || name.endsWith(".txt") -> FileType.CSV
        // .xls and .xlsb reach the reader too, which reports the format precisely rather than
        // failing with a parse error.
        name.endsWith(".xlsx") || name.endsWith(".xlsm") ||
            name.endsWith(".xls") || name.endsWith(".xlsb") -> FileType.XLSX
        name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
            name.endsWith(".webp") || name.endsWith(".heic") || name.endsWith(".bmp") -> FileType.IMAGE
        else -> null
    }

    /**
     * Content-provider URIs frequently arrive with an opaque display name, so the MIME type
     * from the resolver is the only signal left.
     */
    private fun byMimeType(mime: String): FileType? = when {
        mime == "application/pdf" -> FileType.PDF
        mime.startsWith("image/") -> FileType.IMAGE
        mime.contains("spreadsheetml") || mime == "application/vnd.ms-excel" -> FileType.XLSX
        mime == "text/csv" || mime == "text/tab-separated-values" || mime == "text/plain" -> FileType.CSV
        else -> null
    }
}
