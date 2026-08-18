package com.spendwise.di

import com.spendwise.data.repository.AuthRepositoryImpl
import com.spendwise.data.repository.AnalyticsRepositoryImpl
import com.spendwise.data.repository.AssetRepositoryImpl
import com.spendwise.data.repository.InsuranceRepositoryImpl
import com.spendwise.data.repository.LoanDetailsRepositoryImpl
import com.spendwise.data.repository.RiskProfileRepositoryImpl
import com.spendwise.data.repository.ExpenseRepositoryImpl
import com.spendwise.data.repository.GoalRepositoryImpl
import com.spendwise.data.repository.IncomeRepositoryImpl
import com.spendwise.data.repository.RecurringEntryRepositoryImpl
import com.spendwise.domain.repository.AuthRepository
import com.spendwise.domain.repository.AnalyticsRepository
import com.spendwise.domain.repository.AssetRepository
import com.spendwise.domain.repository.InsuranceRepository
import com.spendwise.domain.repository.LoanDetailsRepository
import com.spendwise.domain.repository.RiskProfileRepository
import com.spendwise.domain.repository.ExpenseRepository
import com.spendwise.domain.repository.GoalRepository
import com.spendwise.domain.repository.IncomeRepository
import com.spendwise.domain.repository.RecurringEntryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindExpenseRepository(repository: ExpenseRepositoryImpl): ExpenseRepository

    @Binds
    @Singleton
    abstract fun bindAnalyticsRepository(repository: AnalyticsRepositoryImpl): AnalyticsRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(repository: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindIncomeRepository(repository: IncomeRepositoryImpl): IncomeRepository

    @Binds
    @Singleton
    abstract fun bindGoalRepository(repository: GoalRepositoryImpl): GoalRepository

    @Binds
    @Singleton
    abstract fun bindRecurringEntryRepository(
        repository: RecurringEntryRepositoryImpl
    ): RecurringEntryRepository

    @Binds
    @Singleton
    abstract fun bindAssetRepository(
        repository: AssetRepositoryImpl
    ): AssetRepository

    @Binds
    @Singleton
    abstract fun bindLoanDetailsRepository(
        repository: LoanDetailsRepositoryImpl
    ): LoanDetailsRepository

    @Binds
    @Singleton
    abstract fun bindInsuranceRepository(
        repository: InsuranceRepositoryImpl
    ): InsuranceRepository

    @Binds
    @Singleton
    abstract fun bindRiskProfileRepository(
        repository: RiskProfileRepositoryImpl
    ): RiskProfileRepository
}

