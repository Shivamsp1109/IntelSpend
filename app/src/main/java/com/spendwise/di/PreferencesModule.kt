package com.spendwise.di

import com.spendwise.util.IncomePreferenceStore
import com.spendwise.util.SharedPrefsIncomePreferenceStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PreferencesModule {
    @Binds
    @Singleton
    abstract fun bindIncomePreferenceStore(
        store: SharedPrefsIncomePreferenceStore
    ): IncomePreferenceStore
}
