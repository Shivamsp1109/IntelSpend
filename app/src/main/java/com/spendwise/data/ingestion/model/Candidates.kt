package com.spendwise.data.ingestion.model

data class AmountCandidate(val value: Double, val rawText: String, val line: String, val score: Float)
data class DateCandidate(val value: Long, val rawText: String, val score: Float)
data class MerchantCandidate(val value: String, val score: Float)
data class CurrencyCandidate(val value: com.spendwise.domain.model.Currency, val score: Float)
