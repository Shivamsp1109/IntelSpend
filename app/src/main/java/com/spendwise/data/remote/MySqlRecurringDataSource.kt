package com.spendwise.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.spendwise.data.local.DismissedRecurringCandidateEntity
import com.spendwise.data.local.RecurringEntity
import kotlinx.coroutines.tasks.await
import retrofit2.HttpException
import javax.inject.Inject

class MySqlRecurringDataSource @Inject constructor(
    private val api: MySqlRecurringApi,
    private val firebaseAuth: FirebaseAuth
) {
    private suspend fun bearerToken(): String {
        val user = firebaseAuth.currentUser
            ?: error("Cannot sync recurring entry without an authenticated Firebase user.")
        return "Bearer ${
            user.getIdToken(false).await().token
                ?: error("Cannot sync recurring entry without a Firebase ID token.")
        }"
    }

    suspend fun upsertRecurring(entry: RecurringEntity) {
        val uid = firebaseAuth.currentUser?.uid
            ?: error("Cannot sync recurring entry without an authenticated Firebase user.")
        val response = api.upsertRecurring(
            bearerToken = bearerToken(),
            recurring = entry.toSyncPayload(uid = uid)
        )
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun linkExpense(recurringId: Int, expenseId: Int) {
        val uid = firebaseAuth.currentUser?.uid
            ?: error("Cannot link expense without an authenticated Firebase user.")
        val response = api.linkExpense(
            bearerToken = bearerToken(),
            link = RecurringLinkPayload(
                uid = uid,
                recurringLocalId = recurringId,
                expenseLocalId = expenseId
            )
        )
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun fetchAllRecurring(): List<RecurringSyncPayload> =
        collectPages { token, after -> api.listRecurring(token, after, PAGE_SIZE) }

    suspend fun fetchAllLinks(): List<RecurringLinkRow> =
        collectPages { token, after -> api.listLinks(token, after, PAGE_SIZE) }

    suspend fun fetchAllDismissals(): List<RecurringDismissalRow> =
        collectPages { token, after -> api.listDismissals(token, after, PAGE_SIZE) }

    /**
     * Walks a cursor-paginated endpoint to the end.
     *
     * Bounded by [MAX_PAGES] so a server that keeps returning a cursor cannot
     * spin here forever — a restore that stops early is recoverable, one that
     * never returns is not.
     */
    private suspend fun <T : Any> collectPages(
        fetch: suspend (String, Int) -> retrofit2.Response<RestorePage<T>>
    ): List<T> {
        val token = bearerToken()
        val collected = mutableListOf<T>()
        var after = 0

        repeat(MAX_PAGES) {
            val response = fetch(token, after)
            if (!response.isSuccessful) throw HttpException(response)

            val page = response.body() ?: return collected
            collected += page.items
            after = page.nextAfter ?: return collected
        }
        return collected
    }

    suspend fun upsertDismissal(dismissal: DismissedRecurringCandidateEntity) {
        val uid = firebaseAuth.currentUser?.uid
            ?: error("Cannot sync a dismissal without an authenticated Firebase user.")
        val response = api.upsertDismissal(
            bearerToken = bearerToken(),
            dismissal = dismissal.toSyncPayload(uid = uid)
        )
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun unlinkExpense(recurringId: Int, expenseId: Int) {
        val uid = firebaseAuth.currentUser?.uid
            ?: error("Cannot unlink expense without an authenticated Firebase user.")
        val response = api.unlinkExpense(
            bearerToken = bearerToken(),
            link = RecurringLinkPayload(
                uid = uid,
                recurringLocalId = recurringId,
                expenseLocalId = expenseId
            )
        )
        if (!response.isSuccessful) throw HttpException(response)
    }

    private companion object {
        const val PAGE_SIZE = 200
        const val MAX_PAGES = 200
    }
}
