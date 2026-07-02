package com.spendwise.data.local

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.spendwise.domain.model.ExpenseFilterState
import com.spendwise.domain.model.ExpenseSortOrder

object ExpenseQueryBuilder {
    fun buildPagingQuery(state: ExpenseFilterState): SupportSQLiteQuery {
        val queryStr = StringBuilder("SELECT * FROM expenses WHERE 1=1")
        val args = mutableListOf<Any>()

        if (state.query.isNotBlank()) {
            queryStr.append(" AND (title LIKE ? OR merchant LIKE ? OR category LIKE ?)")
            val likeArg = "%${state.query}%"
            args.add(likeArg)
            args.add(likeArg)
            args.add(likeArg)
        }

        if (state.categories.isNotEmpty()) {
            queryStr.append(" AND category IN (${placeholders(state.categories.size)})")
            args.addAll(state.categories)
        }
        
        if (state.titles.isNotEmpty()) {
            queryStr.append(" AND title IN (${placeholders(state.titles.size)})")
            args.addAll(state.titles)
        }

        if (state.merchants.isNotEmpty()) {
            queryStr.append(" AND merchant IN (${placeholders(state.merchants.size)})")
            args.addAll(state.merchants)
        }

        if (state.currencies.isNotEmpty()) {
            queryStr.append(" AND currency IN (${placeholders(state.currencies.size)})")
            args.addAll(state.currencies)
        }

        if (state.startDate != null) {
            queryStr.append(" AND date >= ?")
            args.add(state.startDate)
        }
        
        if (state.endDate != null) {
            queryStr.append(" AND date <= ?")
            args.add(state.endDate)
        }

        val orderClause = when (state.sortOrder) {
            ExpenseSortOrder.DATE_DESC -> "date DESC"
            ExpenseSortOrder.DATE_ASC -> "date ASC"
            ExpenseSortOrder.AMOUNT_DESC -> "amount DESC"
            ExpenseSortOrder.AMOUNT_ASC -> "amount ASC"
        }
        
        queryStr.append(" ORDER BY $orderClause")

        return SimpleSQLiteQuery(queryStr.toString(), args.toTypedArray())
    }

    private fun placeholders(count: Int): String {
        return List(count) { "?" }.joinToString(",")
    }
}
