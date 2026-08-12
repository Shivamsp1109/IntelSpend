package com.spendwise.data.remote

import android.graphics.Bitmap
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.roundToInt

sealed class ExtractionResult {
    data class Success(val transactions: List<ExtractedTransaction>) : ExtractionResult()
    /** The monthly cap was reached; the local parse is all the user gets this month. */
    data class QuotaExceeded(val message: String) : ExtractionResult()
    data class Failed(val message: String) : ExtractionResult()
}

sealed class NarrativeResult {
    data class Success(val narrative: NarrativeResponse) : NarrativeResult()
    data class QuotaExceeded(val message: String) : NarrativeResult()
    data class Failed(val message: String) : NarrativeResult()
}

class ExtractionDataSource @Inject constructor(
    private val api: ExtractionApi,
    private val firebaseAuth: FirebaseAuth
) {
    suspend fun extract(bitmap: Bitmap, hint: String?): ExtractionResult = withContext(Dispatchers.IO) {
        val user = firebaseAuth.currentUser
            ?: return@withContext ExtractionResult.Failed("Sign in to use smart extraction.")
        val token = runCatching { user.getIdToken(false).await() }.getOrNull()?.token
            ?: return@withContext ExtractionResult.Failed("Could not authenticate the request.")

        val encoded = encode(bitmap)

        try {
            val response = api.extract(
                bearerToken = "Bearer $token",
                request = ExtractionRequest(
                    imageBase64 = encoded,
                    mediaType = MEDIA_TYPE,
                    hint = hint
                )
            )
            when {
                response.isSuccessful ->
                    ExtractionResult.Success(response.body()?.transactions.orEmpty())
                response.code() == 429 ->
                    ExtractionResult.QuotaExceeded(
                        "Monthly smart-extraction limit reached. It resets next month."
                    )
                else ->
                    ExtractionResult.Failed("Smart extraction failed (${response.code()}).")
            }
        } catch (e: Exception) {
            ExtractionResult.Failed("Smart extraction is unreachable: ${e.message}")
        }
    }

    /**
     * Returns cleaned names keyed by the fragment sent, or an empty map on any
     * failure — merchant polish is cosmetic, so it must never break an import
     * that already has correct amounts and dates.
     */
    suspend fun enrichMerchants(names: List<String>): Map<String, EnrichedMerchant> =
        withContext(Dispatchers.IO) {
            if (names.isEmpty()) return@withContext emptyMap()
            val user = firebaseAuth.currentUser ?: return@withContext emptyMap()
            val token = runCatching { user.getIdToken(false).await() }.getOrNull()?.token
                ?: return@withContext emptyMap()

            val response = runCatching {
                api.enrichMerchants("Bearer $token", MerchantEnrichRequest(names))
            }.getOrNull() ?: return@withContext emptyMap()

            if (!response.isSuccessful) return@withContext emptyMap()

            response.body()?.merchants.orEmpty()
                .mapNotNull { enriched ->
                    names.getOrNull(enriched.index)?.let { original -> original to enriched }
                }
                .toMap()
        }

    /**
     * Asks the backend to summarise a period.
     *
     * Unlike merchant enrichment this reports its failures rather than
     * swallowing them: the user pressed a button and is waiting, so silence
     * would read as the feature being broken.
     */
    suspend fun narrate(request: NarrativeRequest): NarrativeResult =
        withContext(Dispatchers.IO) {
            val user = firebaseAuth.currentUser
                ?: return@withContext NarrativeResult.Failed("Sign in to use summaries.")
            val token = runCatching { user.getIdToken(false).await() }.getOrNull()?.token
                ?: return@withContext NarrativeResult.Failed("Could not authenticate the request.")

            try {
                val response = api.narrate("Bearer $token", request)
                val body = response.body()
                when {
                    response.isSuccessful && body != null -> NarrativeResult.Success(body)
                    response.code() == 429 -> NarrativeResult.QuotaExceeded(
                        "Monthly model limit reached. It resets next month."
                    )
                    else -> NarrativeResult.Failed("Could not write a summary (${response.code()}).")
                }
            } catch (e: Exception) {
                NarrativeResult.Failed("Summaries are unreachable: ${e.message}")
            }
        }

    suspend fun usage(): ExtractionUsage? = withContext(Dispatchers.IO) {
        val user = firebaseAuth.currentUser ?: return@withContext null
        val token = runCatching { user.getIdToken(false).await() }.getOrNull()?.token
            ?: return@withContext null
        runCatching { api.usage("Bearer $token") }.getOrNull()?.body()
    }

    /**
     * Downsamples before upload. Image tokens dominate the cost of a call, and
     * beyond roughly [MAX_EDGE] pixels the extra detail buys no accuracy on
     * receipts and screenshots — it just costs more per import.
     */
    private fun encode(bitmap: Bitmap): String {
        val scaled = downscale(bitmap)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= MAX_EDGE) return bitmap
        val ratio = MAX_EDGE.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private companion object {
        const val MEDIA_TYPE = "image/jpeg"
        const val MAX_EDGE = 1568
        const val JPEG_QUALITY = 85
    }
}
