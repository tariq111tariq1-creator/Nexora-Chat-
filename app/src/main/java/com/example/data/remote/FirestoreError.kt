package com.example.data.remote

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

enum class OperationType {
    CREATE,
    UPDATE,
    DELETE,
    LIST,
    GET,
    WRITE
}

sealed interface CloudUiState<out T> {
    data object Idle : CloudUiState<Nothing>
    data object Loading : CloudUiState<Nothing>
    data class Success<T>(val data: T) : CloudUiState<T>
    data class Error(val message: String, val isPermissionDenied: Boolean = false) : CloudUiState<Nothing>
}

fun unwrapFirestoreException(error: Throwable): FirebaseFirestoreException? {
    return generateSequence(error) { it.cause }
        .filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
}

fun handleFirestoreError(
    error: Throwable,
    operationType: OperationType,
    path: String
): String {
    if (error is CancellationException) throw error
    val firestoreEx = unwrapFirestoreException(error)
    val payload = runCatching {
        JSONObject().apply {
            put("operation", operationType.name)
            put("path", path)
            put("code", firestoreEx?.code?.name ?: error::class.java.simpleName)
            put("message", error.message ?: "Unknown Firestore error")
        }.toString()
    }.getOrDefault("${operationType.name} $path failed: ${error.message}")
    Log.e("FirestoreError", payload, error)

    return when (firestoreEx?.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            "Permission denied ($path). Ensure you are signed in and authorized."
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
            "Network unavailable. Changes will sync when reconnected."
        FirebaseFirestoreException.Code.NOT_FOUND ->
            "Requested record was not found."
        else -> error.message ?: "Cloud sync error"
    }
}
