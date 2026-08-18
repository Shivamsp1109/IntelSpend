package com.spendwise.data.remote

import com.google.firebase.auth.FirebaseAuth
import javax.inject.Inject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.tasks.await

/**
 * Asks the server a question and maps what comes back.
 *
 * Built on the model-backed client rather than the sync one: the call runs an
 * intent classification and a composition, and the fast client's read timeout
 * would abandon a request the server is still working on.
 */
class ChatDataSource @Inject constructor(
    private val api: ChatApi,
    private val firebaseAuth: FirebaseAuth
) {
    suspend fun ask(request: ChatRequest): ChatResult {
        return try {
            val user = firebaseAuth.currentUser
                ?: return ChatResult.Failed("Sign in to ask a question.")
            val token = user.getIdToken(false).await().token
                ?: return ChatResult.Failed("Could not confirm who you are. Try again.")

            val response = api.ask("Bearer $token", request)

            when {
                response.isSuccessful -> {
                    val body = response.body()
                        ?: return ChatResult.Failed("The server replied with nothing.")
                    ChatResult.Success(body)
                }
                // The monthly cap, which is its own condition rather than a
                // generic failure: the user has not done anything wrong and it
                // resolves by waiting rather than retrying.
                response.code() == 429 -> ChatResult.QuotaExceeded
                response.code() == 503 -> ChatResult.Failed(
                    "The assistant is not configured on the server."
                )
                else -> ChatResult.Failed("The server could not answer that (${response.code()}).")
            }
        } catch (e: Exception) {
            ChatResult.Failed(describeFailure(e))
        }
    }

    /** See ExtractionDataSource.describeFailure — same reasoning, same conditions. */
    private fun describeFailure(error: Exception): String = when (error) {
        is SocketTimeoutException ->
            "That took too long. The server may still be working — try again."
        is ConnectException ->
            "Could not reach the server. Check it is running and the address is right."
        is UnknownHostException ->
            "Could not find the server. Check the address and your connection."
        is SSLException ->
            "The secure connection to the server failed."
        else -> error.message ?: "Something went wrong asking that."
    }
}

sealed class ChatResult {
    data class Success(val response: ChatResponse) : ChatResult()

    /** The monthly question limit. Resolves by waiting, not retrying. */
    data object QuotaExceeded : ChatResult()

    data class Failed(val message: String) : ChatResult()
}
