package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.InsurancePolicy
import com.spendwise.domain.model.InsuranceType
import com.spendwise.domain.model.PremiumCadence
import com.spendwise.domain.repository.InsuranceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class InsuranceViewModel @Inject constructor(
    private val insuranceRepository: InsuranceRepository
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val uiState: StateFlow<InsuranceUiState> = insuranceRepository.observePolicies()
        .map { policies -> InsuranceUiState(policies = policies, isLoaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsuranceUiState())

    fun save(
        existingId: Int,
        label: String,
        type: InsuranceType,
        provider: String,
        sumAssured: String,
        premiumAmount: String,
        premiumCadence: PremiumCadence,
        policyEndDate: Long?,
        nomineeSet: Boolean?,
        currency: Currency = Currency.INR
    ) {
        viewModelScope.launch {
            if (label.isBlank()) {
                _message.value = "Give this policy a name you'll recognise."
                return@launch
            }
            val cover = sumAssured.toDoubleOrNull()
            if (cover == null || cover <= 0) {
                _message.value = "Enter what this policy would pay out."
                return@launch
            }

            val policy = InsurancePolicy(
                id = existingId,
                label = label.trim(),
                type = type,
                provider = provider.takeIf { it.isNotBlank() },
                sumAssured = cover,
                currency = currency,
                premiumAmount = premiumAmount.toDoubleOrNull(),
                premiumCadence = premiumCadence,
                policyEndDate = policyEndDate,
                // Passed through untouched. Null means the user has not said,
                // which is not the same as saying there is no nominee.
                nomineeSet = nomineeSet
            )

            if (existingId == 0) insuranceRepository.addPolicy(policy)
            else insuranceRepository.updatePolicy(policy)

            _message.value = if (existingId == 0) "Added ${policy.label}." else "Updated ${policy.label}."
        }
    }

    fun delete(policy: InsurancePolicy) {
        viewModelScope.launch {
            insuranceRepository.deletePolicy(policy)
            _message.value = "Removed ${policy.label}."
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

data class InsuranceUiState(
    val policies: List<InsurancePolicy> = emptyList(),
    val isLoaded: Boolean = false
) {
    private val live: List<InsurancePolicy> get() = policies.filterNot { it.hasLapsed() }

    /** Total life cover in force. Lapsed policies are not cover. */
    val lifeCover: Double get() = live.filter { it.type.isLifeCover }.sumOf { it.sumAssured }

    val lapsedCount: Int get() = policies.count { it.hasLapsed() }

    val withoutNominee: Int get() = live.count { it.nomineeSet == false }

    val currency: Currency get() = policies.firstOrNull()?.currency ?: Currency.INR
}
