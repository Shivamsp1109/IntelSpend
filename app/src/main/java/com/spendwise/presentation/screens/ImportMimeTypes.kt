package com.spendwise.presentation.screens

/**
 * MIME types offered by the spreadsheet picker.
 *
 * Kept broad on purpose: content providers are inconsistent about what they report for a
 * .csv or .xlsx, and a type missing from this list makes the file unselectable even though
 * the reader could handle it. `octet-stream` catches providers that report nothing useful —
 * the reader sniffs the actual format from the file's magic bytes anyway.
 */
val SPREADSHEET_MIME_TYPES = arrayOf(
    "text/csv",
    "text/comma-separated-values",
    "application/csv",
    "text/tab-separated-values",
    "text/plain",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
    "application/vnd.ms-excel.sheet.macroEnabled.12",
    "application/octet-stream"
)
