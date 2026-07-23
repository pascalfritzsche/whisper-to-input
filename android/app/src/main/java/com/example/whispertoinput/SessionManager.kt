/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 */

package com.example.whispertoinput

import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

val SESSION_TOKEN = stringPreferencesKey("session-token")

private const val BASE_URL = "https://morgenschiss.de"

sealed class LoginResult {
    data class Success(val session: String) : LoginResult()
    object InvalidCredentials : LoginResult()
    object Locked : LoginResult()
    object Pending : LoginResult()
    object RateLimited : LoginResult()
    data class Rejected(val reason: String) : LoginResult()
    data class NetworkError(val message: String) : LoginResult()
}

// Website (client/Apps/Main/login.js sha256()) hasht das Passwort vor dem Senden -
// der Server-Hash wurde aus diesem SHA-256-Hex gebildet, nicht aus dem Klartext.
private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

data class DictationSettings(val token: String, val url: String, val cleanupMode: String)

sealed class SettingsFetchResult {
    data class Success(val settings: DictationSettings) : SettingsFetchResult()
    object Unauthorized : SettingsFetchResult()
    data class Error(val message: String) : SettingsFetchResult()
}

// Repliziert exakt den Website-Login-Pfad (api/Main/login.mjs) - kein eigener
// App-Auth-Mechanismus. Passwort wird nie persistiert, nur der Session-String.
object SessionManager {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun login(username: String, password: String, stayLoggedIn: Boolean): LoginResult =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/api/login")
                    .header("us", username)
                    .header("pw", sha256Hex(password))
                    .header("stayan", stayLoggedIn.toString())
                    .post("".toRequestBody(null))
                    .build()
                client.newCall(request).execute().use { response ->
                    val bodyText = response.body?.string() ?: ""
                    when (response.code) {
                        200 -> LoginResult.Success(bodyText.trim())
                        401 -> LoginResult.InvalidCredentials
                        423 -> LoginResult.Locked
                        429 -> LoginResult.RateLimited
                        202 -> LoginResult.Pending
                        403 -> {
                            val reason = try {
                                JSONObject(bodyText).optString("reason", "")
                            } catch (e: Exception) {
                                ""
                            }
                            LoginResult.Rejected(reason)
                        }
                        else -> LoginResult.NetworkError("HTTP ${response.code}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LoginResult.NetworkError(e.message ?: "unknown")
            }
        }

    suspend fun fetchSettings(session: String): SettingsFetchResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/api/dictation/token")
                .header("Cookie", "session=$session")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) return@withContext SettingsFetchResult.Unauthorized
                if (!response.isSuccessful) return@withContext SettingsFetchResult.Error("HTTP ${response.code}")
                val json = JSONObject(response.body?.string() ?: return@withContext SettingsFetchResult.Error("empty_response"))
                SettingsFetchResult.Success(
                    DictationSettings(
                        token = json.optString("token"),
                        url = json.optString("url"),
                        cleanupMode = json.optString("cleanupMode", "light")
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SettingsFetchResult.Error(e.message ?: "unknown")
        }
    }
}
