package com.example.data.remote

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.data.local.AppPreferences
import com.example.data.local.LocalAppSettings
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException

data class ResilientAuthSession(
    val uid: String? = null,
    val email: String = "",
    val isOfflineFallback: Boolean = false,
    val connectivityNotice: String? = null
)

object AuthHelper {
    private const val TAG = "Auth"

    @Volatile
    private var explicitSignOutInProgress: Boolean = false

    private val TERMINAL_AUTH_ERROR_CODES = setOf(
        "ERROR_USER_DISABLED",
        "ERROR_USER_NOT_FOUND",
        "ERROR_USER_TOKEN_EXPIRED",
        "ERROR_INVALID_USER_TOKEN"
    )

    fun isConnectivityFirebaseException(error: Throwable): Boolean {
        val causes = generateSequence(error) { it.cause }.toList()
        if (causes.any {
                it is FirebaseAuthException && it.errorCode in TERMINAL_AUTH_ERROR_CODES
            }
        ) {
            return false
        }
        if (causes.any {
                it is FirebaseNetworkException ||
                    it is FirebaseTooManyRequestsException ||
                    it is IOException
            }
        ) {
            return true
        }
        val authErr = causes.filterIsInstance<FirebaseAuthException>().firstOrNull()
        if (authErr != null &&
            (authErr.errorCode == "ERROR_NETWORK_REQUEST_FAILED" || authErr.errorCode == "ERROR_TOO_MANY_REQUESTS")
        ) {
            return true
        }
        val firestoreErr = causes.filterIsInstance<FirebaseFirestoreException>().firstOrNull()
        if (firestoreErr != null && isTransientFirestoreError(firestoreErr)) {
            return true
        }
        val msg = causes.mapNotNull { it.message?.lowercase() }.joinToString(" | ")
        return msg.contains("network") ||
            msg.contains("timeout") ||
            msg.contains("timed out") ||
            msg.contains("unable to resolve host") ||
            msg.contains("failed to connect") ||
            msg.contains("connection reset") ||
            msg.contains("unavailable")
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun resolveAuthStateDecision(
        liveUid: String?,
        liveEmail: String,
        cachedUid: String,
        cachedEmail: String,
        isExplicitSignOut: Boolean,
        isNetworkOnline: Boolean,
        encounteredConnectivityError: Boolean
    ): ResilientAuthSession {
        if (!liveUid.isNullOrBlank()) {
            return ResilientAuthSession(
                uid = liveUid,
                email = liveEmail.ifBlank { cachedEmail },
                isOfflineFallback = false,
                connectivityNotice = if (encounteredConnectivityError || !isNetworkOnline) {
                    "Temporary network drop — keeping your session active."
                } else {
                    null
                }
            )
        }
        if (isExplicitSignOut) {
            return ResilientAuthSession(uid = null, email = "")
        }
        if (cachedUid.isNotBlank() && (!isNetworkOnline || encounteredConnectivityError)) {
            return ResilientAuthSession(
                uid = cachedUid,
                email = cachedEmail,
                isOfflineFallback = true,
                connectivityNotice = "Temporary connection drop — keeping your session active offline."
            )
        }
        return ResilientAuthSession(uid = null, email = "")
    }

    /**
     * Observes FirebaseAuth AuthStateListener + IdTokenListener + ConnectivityManager,
     * catching connectivity-related Firebase exceptions and preserving the persisted
     * user session across temporary network drops instead of redirecting to login.
     */
    fun observeResilientAuthSession(
        context: Context,
        prefs: AppPreferences,
        auth: FirebaseAuth = Firebase.auth
    ): Flow<ResilientAuthSession> = callbackFlow {
        var latestSettings: LocalAppSettings = runCatching { prefs.settingsFlow.first() }.getOrElse { LocalAppSettings() }
        var encounteredConnectivityError = false

        fun emitResolved() {
            val liveUser = auth.currentUser
            if (liveUser != null) {
                explicitSignOutInProgress = false
            }
            val online = runCatching { isNetworkAvailable(context) }.getOrDefault(true)
            val resolved = resolveAuthStateDecision(
                liveUid = liveUser?.uid,
                liveEmail = liveUser?.email.orEmpty(),
                cachedUid = latestSettings.cachedUserUid,
                cachedEmail = latestSettings.cachedUserEmail,
                isExplicitSignOut = explicitSignOutInProgress,
                isNetworkOnline = online,
                encounteredConnectivityError = encounteredConnectivityError
            )
            trySend(resolved)
        }

        val settingsJob = launch {
            prefs.settingsFlow.collect { updated ->
                latestSettings = updated
                emitResolved()
            }
        }

        fun validateLiveUserTokenSafely(firebaseAuth: FirebaseAuth) {
            val user = firebaseAuth.currentUser
            if (user != null) {
                explicitSignOutInProgress = false
                launch {
                    prefs.setAuthenticatedUserSession(user.uid, user.email.orEmpty())
                    try {
                        user.getIdToken(false).await()
                        encounteredConnectivityError = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (isConnectivityFirebaseException(e)) {
                            Log.w(TAG, "Caught connectivity exception during auth token check; preserving session", e)
                            encounteredConnectivityError = true
                        } else {
                            Log.e(TAG, "Non-connectivity Firebase auth token error", e)
                        }
                    }
                    emitResolved()
                }
            } else {
                emitResolved()
            }
        }

        val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            validateLiveUserTokenSafely(firebaseAuth)
        }
        val idTokenListener = FirebaseAuth.IdTokenListener { firebaseAuth ->
            validateLiveUserTokenSafely(firebaseAuth)
        }

        auth.addAuthStateListener(authStateListener)
        auth.addIdTokenListener(idTokenListener)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networkCallback = if (cm != null) {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    encounteredConnectivityError = false
                    val live = auth.currentUser
                    if (live != null) {
                        validateLiveUserTokenSafely(auth)
                    } else if (!explicitSignOutInProgress && latestSettings.cachedUserUid.isNotBlank()) {
                        launch {
                            attemptAutoSignIn(
                                context = context,
                                credentialManager = CredentialManager.create(context),
                                onAuthSuccess = { validateLiveUserTokenSafely(auth) },
                                onUnauthenticated = { emitResolved() },
                                onConnectivityError = {
                                    encounteredConnectivityError = true
                                    emitResolved()
                                },
                                scope = this
                            )
                        }
                    }
                }

                override fun onLost(network: Network) {
                    emitResolved()
                }
            }
        } else {
            null
        }

        if (cm != null && networkCallback != null) {
            runCatching {
                cm.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    networkCallback
                )
            }.onFailure {
                Log.w(TAG, "Could not register auth connectivity callback: ${it.message}")
            }
        }

        // Emit initial snapshot immediately
        validateLiveUserTokenSafely(auth)

        awaitClose {
            settingsJob.cancel()
            auth.removeAuthStateListener(authStateListener)
            auth.removeIdTokenListener(idTokenListener)
            if (cm != null && networkCallback != null) {
                runCatching { cm.unregisterNetworkCallback(networkCallback) }
            }
        }
    }

    fun attemptAutoSignIn(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onUnauthenticated: () -> Unit,
        scope: CoroutineScope,
        onConnectivityError: (Throwable) -> Unit = {}
    ) {
        if (Firebase.auth.currentUser != null) {
            explicitSignOutInProgress = false
            onAuthSuccess()
            return
        }
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onUnauthenticated()
            return
        }
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        scope.launch {
            try {
                val cred = credentialManager.getCredential(context, request).credential
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                    explicitSignOutInProgress = false
                    onAuthSuccess()
                } else {
                    onUnauthenticated()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isConnectivityFirebaseException(e)) {
                    Log.w(TAG, "Auto sign-in hit connectivity error; keeping offline session if present", e)
                    onConnectivityError(e)
                } else {
                    onUnauthenticated()
                }
            }
        }
    }

    fun onGoogleSignInClicked(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        onAuthCancelled: () -> Unit,
        scope: CoroutineScope
    ) {
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
            return
        }
        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        scope.launch {
            try {
                val activity = context as? Activity
                val cred = if (activity != null) {
                    credentialManager.getCredential(activity, request).credential
                } else {
                    credentialManager.getCredential(context, request).credential
                }
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                    explicitSignOutInProgress = false
                    onAuthSuccess()
                } else {
                    onAuthError("Unexpected credential type received.")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
                onAuthCancelled()
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In failed", e)
                onAuthError(e.localizedMessage ?: "Sign in failed")
            }
        }
    }

    fun signOut(
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        beforeAuthSignOut: suspend () -> Unit = {},
        onSignOutComplete: () -> Unit = {}
    ) {
        scope.launch {
            withContext(NonCancellable) {
                explicitSignOutInProgress = true
                try {
                    beforeAuthSignOut()
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear credential state: ${e.message}")
                } finally {
                    Firebase.auth.signOut()
                    onSignOutComplete()
                }
            }
        }
    }
}
