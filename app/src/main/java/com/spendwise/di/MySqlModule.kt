package com.spendwise.di

import com.spendwise.BuildConfig
import com.spendwise.data.remote.MySqlExpenseApi
import com.spendwise.data.remote.MySqlGoalApi
import com.spendwise.data.remote.MySqlIncomeApi
import com.spendwise.data.remote.MySqlRecurringApi
import com.spendwise.data.remote.MySqlUserApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object MySqlModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = if (BuildConfig.DEBUG) {
                        HttpLoggingInterceptor.Level.BASIC
                    } else {
                        HttpLoggingInterceptor.Level.NONE
                    }
                }
            )
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.MYSQL_API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideMySqlExpenseApi(retrofit: Retrofit): MySqlExpenseApi =
        retrofit.create(MySqlExpenseApi::class.java)

    @Provides
    @Singleton
    fun provideMySqlUserApi(retrofit: Retrofit): MySqlUserApi =
        retrofit.create(MySqlUserApi::class.java)

    @Provides
    @Singleton
    fun provideMySqlIncomeApi(retrofit: Retrofit): MySqlIncomeApi =
        retrofit.create(MySqlIncomeApi::class.java)

    @Provides
    @Singleton
    fun provideMySqlGoalApi(retrofit: Retrofit): MySqlGoalApi =
        retrofit.create(MySqlGoalApi::class.java)

    @Provides
    @Singleton
    fun provideMySqlRecurringApi(retrofit: Retrofit): MySqlRecurringApi =
        retrofit.create(MySqlRecurringApi::class.java)
}

