package com.spendwise.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface IncomePreferenceStore {
    val monthlyIncome: StateFlow<Double>
    fun setMonthlyIncome(value: Double)
}

@Singleton
class SharedPrefsIncomePreferenceStore @Inject constructor(
    @ApplicationContext context: Context
) : IncomePreferenceStore {
    private val preferences = context.getSharedPreferences("spendwise_income", Context.MODE_PRIVATE)
    private val _monthlyIncome = MutableStateFlow(
        Double.fromBits(preferences.getLong(KEY_MONTHLY_INCOME, 0.0.toBits()))
    )
    override val monthlyIncome: StateFlow<Double> = _monthlyIncome.asStateFlow()

    override fun setMonthlyIncome(value: Double) {
        preferences.edit()
            .putLong(KEY_MONTHLY_INCOME, value.toBits())
            .apply()
        _monthlyIncome.value = value
    }

    private companion object {
        const val KEY_MONTHLY_INCOME = "monthly_income"
    }
}
