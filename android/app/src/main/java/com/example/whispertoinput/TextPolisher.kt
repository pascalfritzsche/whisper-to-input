/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2025 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TextPolisher {
    private val TAG = "TextPolisher"
    private var currentJob: Job? = null

    fun startAsync(
        context: Context,
        text: String,
        callback: (String?) -> Unit,
        exceptionCallback: (String) -> Unit
    ) {
        suspend fun makeCleanupRequest(): String {
            val endpoint = context.dataStore.data.map { preferences: Preferences ->
                preferences[ENDPOINT] ?: ""
            }.first()

            if (endpoint == "") {
                throw Exception(context.getString(R.string.error_endpoint_unset))
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
            val body = JSONObject().put("text", text).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$endpoint/cleanup")
                .post(body)
                .build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful || response.code / 100 != 2) {
                throw Exception(response.body!!.string().replace('\n', ' '))
            }
            return response.body!!.string().trim()
        }

        val job = CoroutineScope(Dispatchers.Main).launch {
            val (cleanedText, exceptionMessage) = withContext(Dispatchers.IO) {
                try {
                    Pair(makeCleanupRequest(), null)
                } catch (e: CancellationException) {
                    Pair(null, null)
                } catch (e: Exception) {
                    Pair(null, e.message)
                }
            }

            callback.invoke(cleanedText)

            if (!exceptionMessage.isNullOrEmpty()) {
                Log.e(TAG, exceptionMessage)
                exceptionCallback(exceptionMessage)
            }
        }

        currentJob?.cancel()
        currentJob = job
    }

    fun stop() {
        currentJob?.cancel()
        currentJob = null
    }
}
