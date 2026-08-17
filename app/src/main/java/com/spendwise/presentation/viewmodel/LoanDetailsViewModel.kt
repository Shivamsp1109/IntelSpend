package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.InterestCompounding
import com.spendwise.domain.model.LoanDetails
import com.spendwise.domain.model.PrepaymentChargeType
import com.spendwise.domain.model.RateType
import com.spendwise.domain.model.RecurringCadence
import com.spendwise.domain.repository.LoanDetailsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Editing the terms behind one tracked EMI.
 *
 * Everything except the balance is optional, and the form is built around that.
 * Someone who knows what they owe and not their interest rate should be able to
 * record what they know and get a partial answer with the gap named, rather than
 * being blocked at a required field or — worse — nudged into typing a rate they
 * are guessing at, which would make every figure downstream confidently wrong.
 */
@HiltViewModel
class LoanDetailsViewModel @Inject constructor(
    private val repository: LoanDetailsRepository
) : ViewModel() {

    private val _details = MutableStateFlow<LoanDetails?>(null)
    val details: StateFlow<LoanDetails?> = _details.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun load(recurringId: Int) {
        viewModelScope.launch { _details.value = repository.getForRecurring(recurringId) }
    }

    fun save(
        recurringId: Int,
        principal: String,
        rate: String,
        rateType: RateType,
        compounding: InterestCompounding,
        payment: String,
        remainingInstallments: String,
        prepaymentChargeType: PrepaymentChargeType,
        prepaymentChargeValue: String,
        currency: Currency = Currency.INR
    ) {
        viewModelScope.launch {
            val outstanding = principal.toDoubleOrNull()
            if (outstanding == null || outstanding < 0) {
                _message.value = "Enter what is still owed on this loan."
                return@launch
            }

            val existing = repository.getForRecurring(recurringId)
            repository.save(
                LoanDetails(
                    id = existing?.id ?: 0,
                    recurringId = recurringId,
                    principalOutstanding = outstanding,
                    // Stamped now: the user has just told us this is the balance,
                    // so carrying an older date would report a figure they just
                    // confirmed as out of date.
                    outstandingAsOf = System.currentTimeMillis(),
                    currency = currency,
                    // Blank stays null rather than becoming zero. An absent rate
                    // and a rate of nought are different claims, and the engine
                    // refuses to project on the first while happily projecting an
                    // interest-free loan on the second.
                    interestRate = rate.toDoubleOrNull(),
                    rateType = rateType,
                    compounding = compounding,
                    scheduledPayment = payment.toDoubleOrNull(),
                    paymentFrequency = RecurringCadence.MONTHLY,
                    remainingInstallments = remainingInstallments.toIntOrNull(),
                    prepaymentChargeType = prepaymentChargeType,
                    prepaymentChargeValue = prepaymentChargeValue.toDoubleOrNull()
                )
            )
            _details.value = repository.getForRecurring(recurringId)
            _message.value = "Loan terms saved."
        }
    }

    fun clear(recurringId: Int) {
        viewModelScope.launch {
            repository.getForRecurring(recurringId)?.let { repository.delete(it) }
            _details.value = null
            _message.value = "Loan terms removed."
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
