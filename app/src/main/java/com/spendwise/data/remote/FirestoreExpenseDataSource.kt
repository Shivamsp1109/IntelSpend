package com.spendwise.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.spendwise.data.local.ExpenseEntity
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class FirestoreExpenseDataSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val firebaseAuth: FirebaseAuth
) {
    suspend fun upsertExpense(expense: ExpenseEntity) {
        val uid = firebaseAuth.currentUser?.uid ?: return
        firestore.collection("users")
            .document(uid)
            .collection("expenses")
            .document(expense.id.toString())
            .set(expense)
            .await()
    }
}
