package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.domain.model.Asset
import com.spendwise.domain.model.AssetOwnership
import com.spendwise.domain.model.AssetType
import com.spendwise.domain.model.AssetVerification
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.LiquidityClass
import com.spendwise.domain.repository.AssetRepository
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
class AssetsViewModel @Inject constructor(
    private val assetRepository: AssetRepository
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val uiState: StateFlow<AssetsUiState> = assetRepository.observeAssets()
        .map { assets -> AssetsUiState(assets = assets, isLoaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetsUiState())

    fun save(
        existingId: Int,
        label: String,
        type: AssetType,
        value: String,
        liquidity: LiquidityClass,
        ownership: AssetOwnership,
        lockInUntil: Long?,
        accountType: String?,
        currency: Currency = Currency.INR
    ) {
        viewModelScope.launch {
            val amount = value.toDoubleOrNull()
            if (label.isBlank()) {
                _message.value = "Give this holding a name you'll recognise."
                return@launch
            }
            if (amount == null || amount < 0) {
                _message.value = "Enter what it is worth."
                return@launch
            }

            val asset = Asset(
                id = existingId,
                label = label.trim(),
                type = type,
                currentValue = amount,
                currency = currency,
                // Recorded as valued now, because that is when the user told us.
                // Carrying an older date forward would let a figure they have
                // just confirmed be reported as stale.
                valuationDate = System.currentTimeMillis(),
                liquidity = liquidity,
                lockInUntil = lockInUntil,
                ownership = ownership,
                verificationSource = AssetVerification.MANUAL,
                accountType = accountType?.takeIf { it.isNotBlank() }
            )

            if (existingId == 0) assetRepository.addAsset(asset)
            else assetRepository.updateAsset(asset)

            _message.value = if (existingId == 0) "Added ${asset.label}." else "Updated ${asset.label}."
        }
    }

    fun delete(asset: Asset) {
        viewModelScope.launch {
            assetRepository.deleteAsset(asset)
            _message.value = "Removed ${asset.label}."
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

data class AssetsUiState(
    val assets: List<Asset> = emptyList(),
    val isLoaded: Boolean = false
) {
    val total: Double get() = assets.filter { it.ownership != AssetOwnership.FAMILY }.sumOf { it.currentValue }

    /**
     * What could actually be reached in an emergency. Shown next to the total so
     * the gap between "what I own" and "what I could use this week" is visible
     * rather than something the user has to work out for themselves.
     */
    val reachableNow: Double
        get() = assets.filter { it.isReachableNow() }.sumOf { it.currentValue }

    val currency: Currency get() = assets.firstOrNull()?.currency ?: Currency.INR
}
