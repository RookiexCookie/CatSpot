package com.sidespot.auth

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

data class AuthState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Monotonically increasing counter so observers re-trigger even when
     *  [isAuthenticated] stays `true` across back-to-back sign-ins. */
    val version: Int = 0,
)

class AuthManager private constructor(context: Context) {

    companion object {
        private const val TAG = "SidespotAuth"
        private const val PREFS_NAME = "sidespot_auth"
        private const val TOKEN_TYPE_ACCESS = "access_token"
        private const val TOKEN_TYPE_REFRESH = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at_ms"
        private const val KEY_CODE_VERIFIER = "code_verifier"
        private const val KEY_SCOPES_HASH = "scopes_hash"

        private const val CLIENT_ID = "09751e7755284a0bbe10707c6bde85a0"
        private const val REDIRECT_URI = "sidespot://callback"
        private const val SCOPES =
            "streaming playlist-read playlist-read-private user-library-read " +
            "user-library-modify playlist-modify-public playlist-modify-private " +
            "user-read-playback-state user-modify-playback-state " +
            "user-read-currently-playing user-read-private " +
            "user-read-recently-played"
        private const val AUTH_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager =
            instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        val token = prefs.getString(TOKEN_TYPE_ACCESS, null)
        Log.i(TAG, "init: token present=${token != null}")
        _state.value = AuthState(isAuthenticated = token != null)
    }

    fun buildAuthUri(): Uri {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)

        prefs.edit().putString(KEY_CODE_VERIFIER, verifier).commit()

        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()
    }

    suspend fun exchangeCode(code: String) {
        _state.value = _state.value.copy(isLoading = true, error = null)

        val verifier = prefs.getString(KEY_CODE_VERIFIER, null)
        if (verifier == null) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = "Missing code verifier — please try signing in again",
            )
            return
        }

        try {
            val body = mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "client_id" to CLIENT_ID,
                "code_verifier" to verifier,
            )
            val json = postTokenRequest(body)
            saveTokens(json)
            _state.value = AuthState(
                isAuthenticated = true,
                version = _state.value.version + 1,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = "Token exchange failed: ${e.message}",
            )
        }
    }

    suspend fun refreshAccessToken(): String? {
        Log.i(TAG, "refreshAccessToken: entering")
        val refreshToken = prefs.getString(TOKEN_TYPE_REFRESH, null)
        if (refreshToken == null) {
            Log.i(TAG, "refreshAccessToken: refresh token is null")
            return null
        }

        return try {
            val body = mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to CLIENT_ID,
            )
            val json = postTokenRequest(body)
            saveTokens(json)
            Log.i(TAG, "refreshAccessToken: success")
            json.getString("access_token")
        } catch (e: Exception) {
            Log.i(TAG, "refreshAccessToken: failed", e)
            _state.value = _state.value.copy(error = "Token refresh failed: ${e.message}")
            null
        }
    }

    suspend fun getValidAccessToken(): String? {
        val token = prefs.getString(TOKEN_TYPE_ACCESS, null)
        if (token == null) {
            Log.i(TAG, "getValidAccessToken: no access token")
            return null
        }
        val expiresAtStr = prefs.getString(KEY_EXPIRES_AT, null)
        val expiresAt = expiresAtStr?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        val secsLeft = (expiresAt - now) / 1000
        Log.i(TAG, "getValidAccessToken: expiresAt=$expiresAt now=$now secsLeft=$secsLeft")

        // Refresh if token expires within 60 seconds
        return if (now > expiresAt - 60_000) {
            Log.i(TAG, "getValidAccessToken: token expired/expiring, refreshing")
            refreshAccessToken()
        } else {
            token
        }
    }

    fun logout() {
        Log.i(TAG, "logout: called")
        prefs.edit().clear().commit()
        _state.value = AuthState(isAuthenticated = false)
    }

    fun needsReauth(): Boolean {
        val stored = prefs.getString(KEY_SCOPES_HASH, null)
        val computed = scopesHash()
        val result = stored != computed
        Log.i(TAG, "needsReauth: stored=$stored computed=$computed result=$result")
        return result
    }

    private fun scopesHash(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(SCOPES.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun saveTokens(json: JSONObject) {
        prefs.edit()
            .putString(TOKEN_TYPE_ACCESS, json.getString("access_token"))
            .apply {
                if (json.has("refresh_token")) {
                    putString(TOKEN_TYPE_REFRESH, json.getString("refresh_token"))
                }
            }
            .putString(KEY_EXPIRES_AT, (System.currentTimeMillis() + json.getInt("expires_in") * 1000L).toString())
            .putString(KEY_SCOPES_HASH, scopesHash())
            .remove(KEY_CODE_VERIFIER)
            .commit()
        Log.i(TAG, "saveTokens: stored in SharedPreferences")
    }

    private suspend fun postTokenRequest(params: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val url = URL(TOKEN_URL)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                val body = params.entries.joinToString("&") { (k, v) ->
                    "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
                }

                OutputStreamWriter(conn.outputStream).use { it.write(body) }

                if (conn.responseCode != 200) {
                    val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "unknown error"
                    throw Exception("HTTP ${conn.responseCode}: $errorBody")
                }

                val responseBody = conn.inputStream.bufferedReader().readText()
                JSONObject(responseBody)
            } finally {
                conn.disconnect()
            }
        }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            .take(128)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
