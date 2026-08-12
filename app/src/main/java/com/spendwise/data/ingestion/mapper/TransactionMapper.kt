package com.spendwise.data.ingestion.mapper

import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.data.ingestion.model.TransactionType
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.Income
import com.spendwise.domain.model.IncomeSource
import javax.inject.Inject

sealed class Either<out L, out R> {
    data class Left<out L>(val value: L) : Either<L, Nothing>()
    data class Right<out R>(val value: R) : Either<Nothing, R>()
}

class TransactionMapper @Inject constructor() {
    fun map(raw: RawTransaction): Either<Expense, Income> {
        return if (raw.type == TransactionType.DEBIT) {
            Either.Left(
                Expense(
                    title = raw.title,
                    amount = raw.amount,
                    category = raw.category,
                    date = raw.date,
                    merchant = raw.merchant,
                    currency = raw.currency,
                    source = raw.source
                )
            )
        } else {
            Either.Right(
                Income(
                    title = raw.title,
                    amount = raw.amount,
                    currency = raw.currency,
                    source = IncomeSource.MISCELLANEOUS,
                    note = raw.merchant,
                    date = raw.date
                )
            )
        }
    }
}
