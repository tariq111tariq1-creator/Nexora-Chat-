package com.example.data.remote

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

class GoogleAuthManager(private val appContext: Context) {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val credentialManager: CredentialManager = CredentialManager.create(appContext)

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val authStateFlow: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser)
        }
        auth.addAuthStateListener(listener)
        trySend(auth.currentUser)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun attemptSilentSignIn(activityContext: Context): Result<FirebaseUser?> {
        if (auth.currentUser != null) return Result.success(auth.currentUser)
        val serverClientId = appContext.getString(R.string.default_web_client_id)
        if (serverClientId.isBlank()) return Result.success(null)

        return try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(serverClientId)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val response = credentialManager.getCredential(activityContext, request)
            handleCredential(response.credential)
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoCredentialException) {
            Result.success(null)
        } catch (_: GetCredentialCancellationException) {
            Result.success(null)
        } catch (e: Exception) {
            Log.d("GoogleAuthManager", "Silent sign-in skipped: ${e.message}")
            Result.success(null)
        }
    }

    suspend fun signInWithGoogle(activityContext: Context): Result<FirebaseUser> {
        val serverClientId = appContext.getString(R.string.default_web_client_id)
        if (serverClientId.isBlank()) {
            return Result.failure(IllegalStateException("Google Web Client ID is missing in configuration."))
        }

        return try {
            val signInOption = GetSignInWithGoogleOption.Builder(serverClientId).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()
            val response = credentialManager.getCredential(activityContext, request)
            val user = handleCredential(response.credential).getOrThrow()
                ?: return Result.failure(IllegalStateException("Google account sign-in returned no user."))
            Result.success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Result.failure(Exception("Sign-in cancelled"))
        } catch (e: NoCredentialException) {
            Result.failure(Exception("No Google account available on this device. Please add a Google account in Settings."))
        } catch (e: Exception) {
            Log.e("GoogleAuthManager", "Google Sign-In failed", e)
            Result.failure(e)
        }
    }

    private suspend fun handleCredential(credential: androidx.credentials.Credential): Result<FirebaseUser?> {
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return Result.failure(IllegalStateException("Unsupported credential type"))
        }
        val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
        val firebaseCred = GoogleAuthProvider.getCredential(googleCred.idToken, null)
        val authResult = auth.signInWithCredential(firebaseCred).awaitResult()
        return Result.success(authResult.user)
    }

    suspend fun signOut(beforeSignOut: suspend () -> Unit = {}) {
        withContext(NonCancellable) {
            runCatching { beforeSignOut() }
            runCatching { auth.signOut() }
            runCatching {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            }
        }
    }
}
