package com.spendwise.di

import com.spendwise.util.IncomePreferenceStore
import com.spendwise.util.NarrativePreferenceStore
import com.spendwise.util.SharedPrefsIncomePreferenceStore
import com.spendwise.util.SharedPrefsNarrativePreferenceStore
import com.spendwise.util.SharedPrefsSmartExtractionPreferenceStore
import com.spendwise.util.SmartExtractionPreferenceStore
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

    @Binds
    @Singleton
    abstract fun bindSmartExtractionPreferenceStore(
        store: SharedPrefsSmartExtractionPreferenceStore
    ): SmartExtractionPreferenceStore

    @Binds
    @Singleton
    abstract fun bindNarrativePreferenceStore(
        store: SharedPrefsNarrativePreferenceStore
    ): NarrativePreferenceStore
}
