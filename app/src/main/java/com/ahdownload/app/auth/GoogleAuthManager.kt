package com.ahdownload.app.auth

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.util.Base64
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.ahdownload.app.BuildConfig
import com.ahdownload.app.diagnostics.PersistentDiagnosticLogger
import com.ahdownload.core.common.DiagnosticLevel
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface GoogleAuthResult {
    data class Success(val session: GoogleAccountSession) : GoogleAuthResult
    data object ConfigurationMissing : GoogleAuthResult
    data class Cancelled(val message: String) : GoogleAuthResult
    data class Failure(val message: String) : GoogleAuthResult
}

class GoogleAuthManager(
    context: Context,
    private val logger: PersistentDiagnosticLogger,
) {
    private val appContext = context.applicationContext
    private val credentialManager = CredentialManager.create(appContext)
    private val store = GoogleAuthStore(appContext)

    fun existingSession(): GoogleAccountSession? = store.session()

    fun signOut() {
        store.clear()
        logger.log(
            DiagnosticLevel.INFO,
            type = "GOOGLE_AUTH_SIGNED_OUT",
            reason = "تم حذف جلسة حساب Google المحلية.",
            operation = "auth.google.sign_out",
        )
    }

    suspend fun signIn(activity: Activity): GoogleAuthResult = withContext(Dispatchers.Main.immediate) {
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
        if (clientId.isBlank()) {
            logger.log(
                DiagnosticLevel.ERROR,
                type = "GOOGLE_AUTH_CONFIGURATION_MISSING",
                reason = "Google Web Client ID غير مضبوط.",
                operation = "auth.google.sign_in",
            )
            return@withContext GoogleAuthResult.ConfigurationMissing
        }

        logger.log(
            DiagnosticLevel.INFO,
            type = "GOOGLE_AUTH_STARTED",
            reason = "بدء اختيار حساب Google عبر Credential Manager.",
            operation = "auth.google.sign_in",
        )

        try {
            val result = request(activity, clientId, authorizedOnly = true)
            handle(result)
        } catch (_: NoCredentialException) {
            logger.log(
                DiagnosticLevel.INFO,
                type = "GOOGLE_AUTH_NO_AUTHORIZED_ACCOUNT",
                reason = "لا يوجد حساب Google سبق تفويضه للتطبيق؛ سيتم عرض الحسابات المتاحة.",
                operation = "auth.google.sign_in",
            )
            try {
                val result = request(activity, clientId, authorizedOnly = false)
                handle(result)
            } catch (t: Throwable) {
                failure(t)
            }
        } catch (t: Throwable) {
            failure(t)
        }
    }

    private suspend fun request(
        activity: Activity,
        clientId: String,
        authorizedOnly: Boolean,
    ): androidx.credentials.GetCredentialResponse {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(authorizedOnly)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(false)
            .setNonce(secureNonce())
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        return credentialManager.getCredential(
            request = request,
            context = MutableContextWrapper(activity),
        )
    }

    private fun handle(result: androidx.credentials.GetCredentialResponse): GoogleAuthResult {
        val credential: Credential = result.credential
        if (credential !is androidx.credentials.CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleAuthResult.Failure("لم يتم استلام اعتماد Google صالح.")
        }

        return try {
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            val email = google.id.takeIf { it.contains("@") }
                ?: return GoogleAuthResult.Failure("تعذر قراءة بريد حساب Google.")
            val session = GoogleAccountSession(
                id = google.uniqueId,
                email = email,
                displayName = google.displayName,
            )

            store.save(session, google.idToken)
            logger.log(
                DiagnosticLevel.INFO,
                type = "GOOGLE_AUTH_SUCCESS",
                reason = "تم اختيار حساب Google وحفظ جلسة التطبيق محليًا.",
                operation = "auth.google.sign_in",
                context = mapOf("account" to email),
            )
            GoogleAuthResult.Success(session)
        } catch (t: GoogleIdTokenParsingException) {
            failure(t)
        }
    }

    private fun failure(t: Throwable): GoogleAuthResult {
        logger.log(
            DiagnosticLevel.ERROR,
            type = "GOOGLE_AUTH_FAILED",
            reason = t.message ?: t::class.simpleName.orEmpty(),
            operation = "auth.google.sign_in",
            throwable = t,
        )
        val message = when {
            t.message?.contains("cancel", ignoreCase = true) == true ->
                "تم إلغاء اختيار حساب Google."
            else -> "تعذر إكمال تسجيل الدخول بحساب Google."
        }
        return if (message.contains("إلغاء")) {
            GoogleAuthResult.Cancelled(message)
        } else {
            GoogleAuthResult.Failure(message)
        }
    }

    private fun secureNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
        )
    }
}
