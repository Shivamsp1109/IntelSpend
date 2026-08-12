package com.spendwise.data.ingestion.duplicate

import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.data.ingestion.model.TransactionType
import com.spendwise.data.local.ExpenseDao
import com.spendwise.data.local.IncomeDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs

class DuplicateChecker @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val incomeDao: IncomeDao
) {
    suspend fun flagDuplicates(transactions: List<RawTransaction>) = withContext(Dispatchers.IO) {
        if (transactions.isEmpty()) return@withContext
        
        val minDate = transactions.minOf { it.date }
        val maxDate = transactions.maxOf { it.date }
        val twoDaysMillis = 2 * 24 * 60 * 60 * 1000L

        val existingExpenses = expenseDao.getExpensesBetweenDates(minDate - twoDaysMillis, maxDate + twoDaysMillis)
        val existingIncomes = incomeDao.getIncomesBetweenDates(minDate - twoDaysMillis, maxDate + twoDaysMillis)

        for (tx in transactions) {
            tx.isDuplicate = false
            
            if (tx.type == TransactionType.DEBIT) {
                // Check against expenses
                for (ex in existingExpenses) {
                    if (abs(ex.amount - tx.amount) < 0.01 && abs(ex.date - tx.date) <= twoDaysMillis) {
                        if (tx.merchant == null || ex.merchant == null || levenshtein(tx.merchant.lowercase(), ex.merchant.lowercase()) <= 3) {
                            tx.isDuplicate = true
                            tx.isSelected = false
                            break
                        }
                    }
                }
            } else {
                // Check against incomes
                for (inc in existingIncomes) {
                    if (abs(inc.amount - tx.amount) < 0.01 && abs(inc.date - tx.date) <= twoDaysMillis) {
                        tx.isDuplicate = true
                        tx.isSelected = false
                        break
                    }
                }
            }
        }
    }

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLength = lhs.length
        val rhsLength = rhs.length

        var cost = IntArray(lhsLength + 1) { it }
        var newCost = IntArray(lhsLength + 1)

        for (i in 1..rhsLength) {
            newCost[0] = i
            for (j in 1..lhsLength) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = minOf(costInsert, costDelete, costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLength]
    }
}
