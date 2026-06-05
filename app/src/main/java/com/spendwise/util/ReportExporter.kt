package com.spendwise.util

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.spendwise.domain.model.Expense
import java.io.OutputStreamWriter

object ReportExporter {
    fun exportCsv(context: Context, uri: Uri, expenses: List<Expense>) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            OutputStreamWriter(stream).use { writer ->
                writer.appendLine("Title,Amount,Category,Date")
                expenses.forEach { expense ->
                    writer.appendLine(
                        listOf(
                            expense.title.escapeCsv(),
                            expense.amount.toString(),
                            expense.category.label.escapeCsv(),
                            DateUtils.formatDate(expense.date).escapeCsv()
                        ).joinToString(",")
                    )
                }
            }
        }
    }

    fun exportPdf(context: Context, uri: Uri, expenses: List<Expense>) {
        val document = PdfDocument()
        val paint = Paint().apply { textSize = 14f }
        val titlePaint = Paint().apply {
            textSize = 22f
            isFakeBoldText = true
        }
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        canvas.drawText("SpendWise Expense Report", 40f, 50f, titlePaint)
        var y = 90f
        expenses.take(35).forEach { expense ->
            canvas.drawText(
                "${DateUtils.formatDate(expense.date)}  ${expense.category.label}  ${expense.title}  ${CurrencyFormatter.format(expense.amount)}",
                40f,
                y,
                paint
            )
            y += 22f
        }
        document.finishPage(page)
        context.contentResolver.openOutputStream(uri)?.use { document.writeTo(it) }
        document.close()
    }

    private fun String.escapeCsv(): String =
        if (contains(",") || contains("\"")) "\"${replace("\"", "\"\"")}\"" else this
}
