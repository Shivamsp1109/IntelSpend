package com.spendwise.util

import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object CurrencyRateService {
    private const val BASE_URL = "https://api.frankfurter.app"
    private val cache = ConcurrentHashMap<String, Double>()

    suspend fun rateToInr(currency: String): Double {
        val normalizedCurrency = currency.uppercase()
        if (normalizedCurrency == "INR") return 1.0

        val rateDate = LocalDate.now(ZoneOffset.UTC).minusDays(1).format(DateTimeFormatter.ISO_DATE)
        val cacheKey = "$rateDate:$normalizedCurrency"
        return cache[cacheKey] ?: fetchRate(rateDate, normalizedCurrency).also { rate ->
            cache[cacheKey] = rate
        }
    }

    private suspend fun fetchRate(rateDate: String, currency: String): Double = withContext(Dispatchers.IO) {
        runCatching {
            fetch("$BASE_URL/$rateDate?from=$currency&to=INR")
        }.getOrElse {
            fetch("$BASE_URL/latest?from=$currency&to=INR")
        }
    }

    private fun fetch(url: String): Double {
        val response = URL(url).readText()
        val rates = JSONObject(response).getJSONObject("rates")
        return rates.getDouble("INR")
    }
}
