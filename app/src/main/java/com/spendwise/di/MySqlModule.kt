package com.spendwise.di

import com.spendwise.BuildConfig
import com.spendwise.data.remote.ExtractionApi
import com.spendwise.data.remote.MySqlAssetApi
import com.spendwise.data.remote.MySqlBudgetApi
import com.spendwise.data.remote.MySqlInsuranceApi
import com.spendwise.data.remote.MySqlLoanDetailsApi
import com.spendwise.data.remote.MySqlRiskProfileApi
import com.spendwise.data.remote.MySqlExpenseApi
import com.spendwise.data.remote.MySqlGoalApi
import com.spendwise.data.remote.MySqlIncomeApi
import com.spendwise.data.remote.MySqlRecurringApi
import com.spendwise.data.remote.MySqlUserApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** The client used for model-backed endpoints, which are slow by nature. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ModelBacked

@Module
@InstallIn(SingletonComponent::class)
object MySqlModule {

    /**
     * The client for ordinary sync.
     *
     * Timeouts are stated rather than left to OkHttp's defaults, so they are
     * visible and can be reasoned about. These calls carry a single row and
     * should fail quickly — a sync that hangs is retried by WorkManager anyway,
     * so waiting is worse than giving up.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        baseClient()
            .connectTimeout(SYNC_CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(SYNC_READ_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(SYNC_READ_SECONDS, TimeUnit.SECONDS)
            .build()

    /**
     * The client for extraction, categorisation and summaries.
     *
     * These cannot meet a ten-second budget and never could: the request uploads
     * up to several megabytes of base64 image, the server then waits on a vision
     * model, and a low-confidence read is retried on a second, slower model — so
     * one import can be two full model calls end to end. Against the default
     * timeouts every extraction failed the same way, reporting "unreachable:
     * timeout" while the server was working normally and about to answer.
     *
     * The write timeout matters as much as the read one. On mobile data the
     * upload alone can outlast ten seconds before the model has seen anything.
     */
    @Provides
    @Singleton
    @ModelBacked
    fun provideModelOkHttpClient(): OkHttpClient =
        baseClient()
            .connectTimeout(MODEL_CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(MODEL_READ_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(MODEL_WRITE_SECONDS, TimeUnit.SECONDS)
            // A ceiling on the whole call, retries included, so a pathological
            // request cannot hold the import screen open indefinitely.
            .callTimeout(MODEL_CALL_SECONDS, TimeUnit.SECONDS)
            .build()

    private fun baseClient(): OkHttpClient.Builder =
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
            .apply { certificatePinner()?.let { certificatePinner(it) } }

    private const val SYNC_CONNECT_SECONDS = 15L
    private const val SYNC_READ_SECONDS = 30L

    private const val MODEL_CONNECT_SECONDS = 20L

    /** A vision read plus a possible escalation to a second model. */
    private const val MODEL_READ_SECONDS = 120L

    /** Several megabytes of base64 over mobile data. */
    private const val MODEL_WRITE_SECONDS = 120L

    private const val MODEL_CALL_SECONDS = 180L

    /**
     * Pins the API's certificate, when pins have been configured for this build.
     *
     * TLS on its own trusts every certificate authority the device does, and a
     * device can be made to trust more — a corporate MDM profile, or a user
     * talked into installing a proxy's root certificate. Either turns an
     * encrypted connection into one someone else can read, which for bank
     * statements is the whole thing. Pinning says: this host, these keys, no
     * matter who else the device trusts.
     *
     * Returns null when unconfigured, and pinning is simply off. The alternative
     * — pinning to a placeholder — would refuse every connection, and an app
     * that cannot reach its backend cannot be fixed without a new release.
     */
    private fun certificatePinner(): CertificatePinner? {
        val pins = BuildConfig.API_CERTIFICATE_PINS
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (pins.isEmpty()) return null

        val host = BuildConfig.MYSQL_API_BASE_URL.toHttpUrlOrNull()?.host ?: return null

        return CertificatePinner.Builder()
            // Scoped to our own host. Firebase and Google's endpoints rotate on
            // their own schedule and are validated by their own libraries;
            // pinning them here would break the app on somebody else's timetable.
            .apply { pins.forEach { pin -> add(host, pin) } }
            .build()
    }

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

    @Provides
    @Singleton
    fun provideMySqlBudgetApi(retrofit: Retrofit): MySqlBudgetApi =
        retrofit.create(MySqlBudgetApi::class.java)

    @Provides
    @Singleton
    fun provideMySqlAssetApi(retrofit: Retrofit): MySqlAssetApi =
        retrofit.create(MySqlAssetApi::class.java)

    @Provides
    @Singleton
    fun provideMySqlLoanDetailsApi(retrofit: Retrofit): MySqlLoanDetailsApi =
        retrofit.create(MySqlLoanDetailsApi::class.java)

    @Provides
    @Singleton
    fun provideMySqlInsuranceApi(retrofit: Retrofit): MySqlInsuranceApi =
        retrofit.create(MySqlInsuranceApi::class.java)

    @Provides
    @Singleton
    fun provideMySqlRiskProfileApi(retrofit: Retrofit): MySqlRiskProfileApi =
        retrofit.create(MySqlRiskProfileApi::class.java)

    /**
     * Built on its own Retrofit so it picks up the longer-timeout client. Same
     * base URL and converter — only the patience differs.
     */
    @Provides
    @Singleton
    fun provideExtractionApi(@ModelBacked okHttpClient: OkHttpClient): ExtractionApi =
        Retrofit.Builder()
            .baseUrl(BuildConfig.MYSQL_API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ExtractionApi::class.java)
}

