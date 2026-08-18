package com.spendwise.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface ChatApi {
    @POST("chat")
    suspend fun ask(
        @Header("Authorization") bearerToken: String,
        @Body request: ChatRequest
    ): Response<ChatResponse>

    @GET("chat/{conversationId}")
    suspend fun history(
        @Header("Authorization") bearerToken: String,
        @Path("conversationId") conversationId: String
    ): Response<ChatHistoryResponse>
}

/**
 * What goes to the server when a question is asked.
 *
 * Carries no financial data at all — the server assembles the assessment from
 * what it already holds. The device sends a question, a conversation id, its
 * timezone and how much it still has to sync.
 *
 * That last field is the one worth noticing. The server's copy is a lower bound
 * on what the user has recorded, and an answer built while eleven expenses were
 * still on the handset is a different answer. Sending the count lets the reply
 * say so rather than sounding equally confident either way.
 */
data class ChatRequest(
    val message: String,
    val conversationId: String?,
    val history: List<ChatTurnPayload>,
    val timezone: String,
    val currency: String,
    val pendingLocalChanges: Int,
    val lastSuccessfulSyncAt: Long
)

data class ChatTurnPayload(val role: String, val content: String)

data class ChatResponse(
    val conversationId: String?,
    val traceId: String?,
    val snapshotId: String?,
    val intent: String?,
    val paragraphs: List<String>?,
    val suggestedFollowUps: List<String>?,
    val dataQuality: ChatDataQualityPayload?,
    /** True when the numeric gate refused the model's wording and the engine answered. */
    val wordingFallback: Boolean?,
    val callsUsedThisMonth: Int?,
    val monthlyCallCap: Int?
)

data class ChatDataQualityPayload(
    val confidence: String?,
    val caveats: List<String>?
)

data class ChatHistoryResponse(
    val conversationId: String?,
    val messages: List<ChatHistoryMessage>?
)

data class ChatHistoryMessage(
    val role: String?,
    val content: String?,
    val intent: String?,
    val decisionTraceId: String?,
    val rejectedReason: String?,
    val createdAt: Long?
)
