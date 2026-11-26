package com.example.data.remote

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(exception: Throwable, operationType: OperationType, path: String?): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

fun unwrapFirestoreError(error: Throwable): Throwable {
    val firestoreError = generateSequence(error) { it.cause }
        .filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
    if (firestoreError == null && error is CancellationException) throw error
    return firestoreError ?: error
}

fun isTransientFirestoreError(error: Throwable): Boolean {
    val root = generateSequence(error) { it.cause }.firstOrNull {
        it is FirebaseFirestoreException || it is IOException
    } ?: error
    if (root is IOException) return true
    if (root is FirebaseFirestoreException) {
        return when (root.code) {
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
            FirebaseFirestoreException.Code.ABORTED,
            FirebaseFirestoreException.Code.INTERNAL,
            FirebaseFirestoreException.Code.UNKNOWN,
            FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED,
            FirebaseFirestoreException.Code.CANCELLED -> true
            else -> false
        }
    }
    return false
}

fun <T> Flow<T>.retryTransientFirestore(
    tag: String = "FirestoreRetry",
    onTransientError: ((Throwable) -> Unit)? = null,
    onRecovered: (() -> Unit)? = null
): Flow<T> {
    return this
        .onEach { onRecovered?.invoke() }
        .retryWhen { cause, attempt ->
            if (cause is CancellationException) throw cause
            val unwrapped = runCatching { unwrapFirestoreError(cause) }.getOrElse { cause }
            if (isTransientFirestoreError(unwrapped)) {
                onTransientError?.invoke(unwrapped)
                val shift = attempt.coerceAtMost(4).toInt()
                val waitMs = minOf(12_000L, 1_000L * (1L shl shift))
                Log.w(tag, "Transient Firestore error (${unwrapped.message}); retrying in ${waitMs}ms")
                delay(waitMs)
                true
            } else {
                false
            }
        }
}
