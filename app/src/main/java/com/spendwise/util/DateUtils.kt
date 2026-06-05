package com.spendwise.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateUtils {
    private val displayFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    fun formatDate(timestamp: Long): String = displayFormat.format(Date(timestamp))

    fun isToday(timestamp: Long): Boolean = sameDay(timestamp, System.currentTimeMillis())

    fun isThisMonth(timestamp: Long): Boolean {
        val expense = Calendar.getInstance().apply { timeInMillis = timestamp }
        val now = Calendar.getInstance()
        return expense.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            expense.get(Calendar.MONTH) == now.get(Calendar.MONTH)
    }

    fun isPreviousMonth(timestamp: Long): Boolean {
        val expense = Calendar.getInstance().apply { timeInMillis = timestamp }
        val previous = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }
        return expense.get(Calendar.YEAR) == previous.get(Calendar.YEAR) &&
            expense.get(Calendar.MONTH) == previous.get(Calendar.MONTH)
    }

    fun monthKey(timestamp: Long): String =
        SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(timestamp))

    fun dayOfWeek(timestamp: Long): String =
        SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))

    private fun sameDay(first: Long, second: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = first }
        val b = Calendar.getInstance().apply { timeInMillis = second }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
}
