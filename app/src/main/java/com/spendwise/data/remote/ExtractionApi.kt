package com.spendwise.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Model-backed extraction, served by the SpendWise backend.
 *
 * The provider API key lives on the server, never in the APK, so the model can
 * be swapped without shipping a release and a decompiled app leaks nothing.
 * This contract is provider-agnostic — the backend currently calls Gemini.
 */
interface ExtractionApi {
    @POST("extract")
    suspend fun extract(
        @Header("Authorization") bearerToken: String,
        @Body request: ExtractionRequest
    ): Response<ExtractionResponse>

    /**
     * Cleans up payee names read from bank narrations. Text-only, so one call
     * covers a whole statement at a fraction of an image call's cost.
     */
    @POST("extract/merchants")
    suspend fun enrichMerchants(
        @Header("Authorization") bearerToken: String,
        @Body request: MerchantEnrichRequest
    ): Response<MerchantEnrichResponse>

    /**
     * Writes a plain-English summary of a period from figures already computed
     * on the device. Text-only and small — no transaction rows are sent.
     */
    @POST("extract/narrative")
    suspend fun narrate(
        @Header("Authorization") bearerToken: String,
        @Body request: NarrativeRequest
    ): Response<NarrativeResponse>

    @GET("extract/usage")
    suspend fun usage(
        @Header("Authorization") bearerToken: String
    ): Response<ExtractionUsage>
}

data class ExtractionRequest(
    @SerializedName("imageBase64") val imageBase64: String,
    @SerializedName("mediaType") val mediaType: String,
    /** What the app already believes about the file, e.g. "payment screenshot". */
    @SerializedName("hint") val hint: String? = null
)

data class ExtractionResponse(
    @SerializedName("documentType") val documentType: String? = null,
    @SerializedName("transactions") val transactions: List<ExtractedTransaction> = emptyList(),
    @SerializedName("model") val model: String? = null,
    @SerializedName("callsUsedThisMonth") val callsUsedThisMonth: Int = 0,
    @SerializedName("monthlyCallCap") val monthlyCallCap: Int = 0
)

data class ExtractedTransaction(
    @SerializedName("amount") val amount: Double = 0.0,
    @SerializedName("currency") val currency: String = "INR",
    /** ISO yyyy-MM-dd, or null when the document shows no date. */
    @SerializedName("date") val date: String? = null,
    @SerializedName("merchant") val merchant: String = "",
    @SerializedName("direction") val direction: String = "DEBIT",
    @SerializedName("category") val category: String = "Other",
    @SerializedName("confidence") val confidence: Double = 0.0,
    /** Verbatim text the amount was read from — checked against OCR before we trust it. */
    @SerializedName("amountSource") val amountSource: String = ""
)

data class MerchantEnrichRequest(
    /** Deduplicated payee fragments, in the order the indices refer to. */
    @SerializedName("names") val names: List<String>
)

data class MerchantEnrichResponse(
    @SerializedName("merchants") val merchants: List<EnrichedMerchant> = emptyList()
)

data class EnrichedMerchant(
    /** Position in the request list — the model echoes it back to pair them up. */
    @SerializedName("index") val index: Int = -1,
    @SerializedName("merchant") val merchant: String = "",
    @SerializedName("category") val category: String = "Other",
    @SerializedName("confidence") val confidence: Double = 0.0
)

/**
 * Aggregates only. Deliberately carries no transaction rows, ids, dates or
 * notes — a summary is written from totals, and sending the underlying rows to
 * a third party would be handing over far more than the job needs.
 */
data class NarrativeRequest(
    @SerializedName("periodLabel") val periodLabel: String,
    @SerializedName("currency") val currency: String,
    @SerializedName("totalExpense") val totalExpense: Double,
    @SerializedName("totalIncome") val totalIncome: Double,
    @SerializedName("previousExpense") val previousExpense: Double,
    @SerializedName("averagePerDay") val averagePerDay: Double,
    @SerializedName("transactionCount") val transactionCount: Int,
    @SerializedName("topCategories") val topCategories: List<NamedAmount>,
    @SerializedName("topMerchants") val topMerchants: List<NamedAmount>,
    /** The rule-based findings, so the model describes them rather than reinterpreting. */
    @SerializedName("highlights") val highlights: List<String>
)

data class NamedAmount(
    @SerializedName("name") val name: String,
    @SerializedName("amount") val amount: Double,
    @SerializedName("count") val count: Int? = null
)

data class NarrativeResponse(
    @SerializedName("headline") val headline: String = "",
    @SerializedName("narrative") val narrative: String = "",
    @SerializedName("suggestions") val suggestions: List<String> = emptyList(),
    @SerializedName("callsUsedThisMonth") val callsUsedThisMonth: Int = 0,
    @SerializedName("monthlyCallCap") val monthlyCallCap: Int = 0
)

data class ExtractionUsage(
    @SerializedName("callsThisMonth") val callsThisMonth: Int = 0,
    @SerializedName("monthlyCallCap") val monthlyCallCap: Int = 0,
    @SerializedName("inputTokens") val inputTokens: Long = 0,
    @SerializedName("outputTokens") val outputTokens: Long = 0,
    @SerializedName("estimatedCostUsd") val estimatedCostUsd: Double = 0.0
)
