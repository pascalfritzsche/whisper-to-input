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

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.core.app.ActivityCompat
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.*
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// 200 and 201 are an arbitrary values, as long as they do not conflict with each other
private const val MICROPHONE_PERMISSION_REQUEST_CODE = 200
private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 201
// Rein informativ im Settings-Screen - App hat keine Modellwahl mehr, muss manuell
// nachgezogen werden falls sich GENERATE_MODEL in api/MulmAI/asr.mjs aendert.
private const val CURRENT_SERVER_MODEL = "gemma4:e4b-mlx"
private val cleanupModeSyncClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .build()
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
val SPEECH_TO_TEXT_BACKEND = stringPreferencesKey("speech-to-text-backend")
val ENDPOINT = stringPreferencesKey("endpoint")
val AUTO_RECORDING_START = booleanPreferencesKey("is-auto-recording-start")
val AUTO_SWITCH_BACK = booleanPreferencesKey("auto-switch-back")
val ADD_TRAILING_SPACE = booleanPreferencesKey("add-trailing-space")
val POSTPROCESSING = stringPreferencesKey("postprocessing")

class MainActivity : AppCompatActivity() {
    private var setupSettingItemsDone: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        checkPermissions()
        checkAuthState()
    }

    private fun checkAuthState() {
        CoroutineScope(Dispatchers.Main).launch {
            val session = dataStore.data.map { it[SESSION_TOKEN] }.first()
            if (session.isNullOrEmpty()) {
                showLoginScreen()
            } else {
                loadSettingsForSession(session)
            }
        }
    }

    private fun showLoginScreen() {
        findViewById<View>(R.id.settings_top_bar).visibility = View.GONE
        findViewById<View>(R.id.update_banner).visibility = View.GONE
        findViewById<View>(R.id.token_fetch_error_banner).visibility = View.GONE
        findViewById<View>(R.id.settings_scroll).visibility = View.GONE
        findViewById<View>(R.id.login_container).visibility = View.VISIBLE
        setupLoginScreen()
    }

    private fun showSettingsScreen() {
        findViewById<View>(R.id.login_container).visibility = View.GONE
        findViewById<View>(R.id.settings_top_bar).visibility = View.VISIBLE
        findViewById<View>(R.id.settings_scroll).visibility = View.VISIBLE
        setupSettingItems()
        setupLogoutButton()
        checkForAppUpdate()
    }

    private suspend fun loadSettingsForSession(session: String) {
        when (val result = SessionManager.fetchSettings(session)) {
            is SettingsFetchResult.Success -> {
                dataStore.edit {
                    it[ENDPOINT] = result.settings.url
                    // Server ist die Quelle der Wahrheit fuer den Cleanup-Modus - lokale
                    // Preference bei jedem Sync ueberschreiben, nicht nur beim ersten Login.
                    it[POSTPROCESSING] = result.settings.cleanupMode
                }
                showSettingsScreen()
            }
            is SettingsFetchResult.Unauthorized -> {
                dataStore.edit { it.remove(SESSION_TOKEN) }
                showLoginScreen()
            }
            is SettingsFetchResult.Error -> {
                // Session war laut Server nicht abgelehnt (kein 401) - Settings trotzdem
                // zeigen (mit evtl. altem Endpoint) + Retry-Banner statt komplett zu blockieren.
                showSettingsScreen()
                val banner = findViewById<View>(R.id.token_fetch_error_banner)
                banner.visibility = View.VISIBLE
                findViewById<Button>(R.id.btn_token_fetch_retry).setOnClickListener {
                    banner.visibility = View.GONE
                    CoroutineScope(Dispatchers.Main).launch { loadSettingsForSession(session) }
                }
            }
        }
    }

    private fun setupLoginScreen() {
        val btnLogin: Button = findViewById(R.id.btn_login)
        val fieldUsername: EditText = findViewById(R.id.field_login_username)
        val fieldPassword: EditText = findViewById(R.id.field_login_password)
        val checkboxStay: CheckBox = findViewById(R.id.checkbox_stay_logged_in)

        btnLogin.setOnClickListener {
            val username = fieldUsername.text.toString()
            val password = fieldPassword.text.toString()
            if (username.isEmpty() || password.isEmpty()) return@setOnClickListener
            findViewById<View>(R.id.label_login_error).visibility = View.GONE
            btnLogin.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                val result = SessionManager.login(username, password, checkboxStay.isChecked)
                btnLogin.isEnabled = true
                when (result) {
                    is LoginResult.Success -> {
                        dataStore.edit { it[SESSION_TOKEN] = result.session }
                        loadSettingsForSession(result.session)
                    }
                    is LoginResult.InvalidCredentials -> showLoginError(getString(R.string.error_login_invalid))
                    is LoginResult.Locked -> showLoginError(getString(R.string.error_login_locked))
                    is LoginResult.Pending -> showLoginError(getString(R.string.error_login_pending))
                    is LoginResult.RateLimited -> showLoginError(getString(R.string.error_login_rate_limited))
                    is LoginResult.Rejected -> showLoginError(result.reason.ifBlank { getString(R.string.error_login_rejected) })
                    is LoginResult.NetworkError -> showLoginError(getString(R.string.error_login_network))
                }
            }
        }
    }

    private fun showLoginError(message: String) {
        val labelError: android.widget.TextView = findViewById(R.id.label_login_error)
        labelError.text = message
        labelError.visibility = View.VISIBLE
    }

    private fun setupLogoutButton() {
        findViewById<View>(R.id.btn_logout).setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                dataStore.edit { it.remove(SESSION_TOKEN) }
                showLoginScreen()
            }
        }
    }

    private fun checkForAppUpdate() {
        CoroutineScope(Dispatchers.Main).launch {
            val update = UpdateChecker.checkForUpdate(this@MainActivity) ?: return@launch

            val banner: View = findViewById(R.id.update_banner)
            val label: android.widget.TextView = findViewById(R.id.label_update_available)
            val btnUpdate: Button = findViewById(R.id.btn_update_now)

            label.text = getString(R.string.update_available, update.versionName)
            banner.visibility = View.VISIBLE
            btnUpdate.setOnClickListener {
                btnUpdate.isEnabled = false
                Toast.makeText(this@MainActivity, R.string.update_downloading, Toast.LENGTH_SHORT).show()
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        UpdateChecker.downloadAndInstall(this@MainActivity, update.downloadUrl)
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.update_download_failed, e.message),
                            Toast.LENGTH_LONG
                        ).show()
                        btnUpdate.isEnabled = true
                    }
                }
            }
        }
    }

    // The onClick event of the grant permission button.
    // Opens up the app settings panel to manually configure permissions.
    fun onRequestMicrophonePermission(view: View) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        with(intent) {
            data = Uri.fromParts("package", packageName, null)
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

        startActivity(intent)
    }

    // Checks whether permissions are granted. If not, automatically make a request.
    private fun checkPermissions() {
        val permission_and_code = arrayOf(
            Pair(Manifest.permission.RECORD_AUDIO, MICROPHONE_PERMISSION_REQUEST_CODE),
            Pair(Manifest.permission.POST_NOTIFICATIONS, NOTIFICATION_PERMISSION_REQUEST_CODE),
        )
        for ((permission, code) in permission_and_code) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) == PackageManager.PERMISSION_DENIED
            ) {
                // Shows a popup for permission request.
                // If the permission has been previously (hard-)denied, the popup will not show.
                // onRequestPermissionsResult will be called in either case.
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(permission),
                    code
                )
            }
        }
    }

    // Handles the results of permission requests.
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        var msg: String

        // Only handles requests marked with the unique code.
        if (requestCode == MICROPHONE_PERMISSION_REQUEST_CODE) {
            msg = getString(R.string.mic_permission_required)
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            msg = getString(R.string.notification_permission_required)
        } else {
            return
        }

        // All permissions should be granted.
        for (result in grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                return
            }
        }
    }

    // Below are settings related functions
    abstract inner class SettingItem() {
        protected var isDirty: Boolean = false
        abstract fun setup() : Job
        abstract suspend fun apply()
        protected suspend fun <T> readSetting(key: Preferences.Key<T>): T? {
            // work is moved to `Dispatchers.IO` under the hood
            // Ref: https://developer.android.com/codelabs/android-preferences-datastore#3
            return dataStore.data.map { preferences ->
                preferences[key]
            }.first()
        }
        protected suspend fun <T> writeSetting(key: Preferences.Key<T>, newValue: T) {
            // work is moved to `Dispatchers.IO` under the hood
            // Ref: https://developer.android.com/codelabs/android-preferences-datastore#3
            dataStore.edit { settings ->
                settings[key] = newValue
            }
        }
    }

    inner class SettingText(
        private val viewId: Int,
        private val preferenceKey: Preferences.Key<String>,
        private val defaultValue: String = ""
    ): SettingItem() {
        override fun setup(): Job {
            return CoroutineScope(Dispatchers.Main).launch {
                val btnApply: Button = findViewById(R.id.btn_settings_apply)
                val editText = findViewById<EditText>(viewId)
                editText.isEnabled = false
                editText.doOnTextChanged { _, _, _, _ ->
                    if (!setupSettingItemsDone) return@doOnTextChanged
                    isDirty = true
                    btnApply.isEnabled = true
                }

                // Read data. If none, apply default value.
                val settingValue: String? = readSetting(preferenceKey)
                val value: String = settingValue ?: defaultValue
                if (settingValue == null) {
                    writeSetting(preferenceKey, defaultValue)
                }
                editText.setText(value)
                editText.isEnabled = true
            }
        }
        override suspend fun apply() {
            if (!isDirty) return
            val newValue: String = findViewById<EditText>(viewId).text.toString()
            writeSetting(preferenceKey, newValue)
            isDirty = false
        }
    }

    inner class SettingDropdown(
        private val viewId: Int,
        private val preferenceKey: Preferences.Key<Boolean>,
        private val stringToValue: HashMap<String, Boolean>,
        private val defaultValue: Boolean = true
    ): SettingItem() {
        override fun setup(): Job {
            return CoroutineScope(Dispatchers.Main).launch {
                val btnApply: Button = findViewById(R.id.btn_settings_apply)
                val spinner = findViewById<Spinner>(viewId)
                spinner.isEnabled = false
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                        if (!setupSettingItemsDone) return
                        isDirty = true
                        btnApply.isEnabled = true
                    }
                    override fun onNothingSelected(parent: AdapterView<*>) { }
                }

                val valueToString = stringToValue.map { (k, v) -> v to k }.toMap()
                // Read data. If none, apply default value.
                val settingValue: Boolean? = readSetting(preferenceKey)
                val value: Boolean = settingValue ?: defaultValue
                val string: String = valueToString[value]!!
                if (settingValue == null) {
                    writeSetting(preferenceKey, defaultValue)
                }
                val index: Int? = (0 until spinner.adapter.count).firstOrNull {
                    spinner.adapter.getItem(it) == string
                }
                spinner.setSelection(index!!, false)
                spinner.isEnabled = true
            }
        }
        override suspend fun apply() {
            if (!isDirty) return
            val selectedItem = findViewById<Spinner>(viewId).selectedItem
            val newValue: Boolean = stringToValue[selectedItem]!!
            writeSetting(preferenceKey, newValue)
            isDirty = false
        }
    }

    // Cleanup-Modus lebt server-seitig am Diktier-Token (wie cleanupEnabled bisher), nicht nur
    // lokal - die Auswahl wird beim Anwenden zusaetzlich an den Server gemeldet.
    inner class SettingCleanupMode(
        private val viewId: Int,
        private val preferenceKey: Preferences.Key<String>,
        private val displayToMode: HashMap<String, String>,
        private val defaultValue: String
    ): SettingItem() {
        override fun setup(): Job {
            return CoroutineScope(Dispatchers.Main).launch {
                val btnApply: Button = findViewById(R.id.btn_settings_apply)
                val spinner = findViewById<Spinner>(viewId)
                spinner.isEnabled = false
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                        if (!setupSettingItemsDone) return
                        isDirty = true
                        btnApply.isEnabled = true
                    }
                    override fun onNothingSelected(parent: AdapterView<*>) { }
                }

                val modeToDisplay = displayToMode.map { (k, v) -> v to k }.toMap()
                val settingValue: String? = readSetting(preferenceKey)
                val value: String = settingValue ?: defaultValue
                if (settingValue == null) {
                    writeSetting(preferenceKey, defaultValue)
                }
                val display = modeToDisplay[value] ?: modeToDisplay[defaultValue]!!
                val index: Int? = (0 until spinner.adapter.count).firstOrNull {
                    spinner.adapter.getItem(it) == display
                }
                spinner.setSelection(index ?: 0, false)
                spinner.isEnabled = true
            }
        }
        override suspend fun apply() {
            if (!isDirty) return
            val selectedItem = findViewById<Spinner>(viewId).selectedItem.toString()
            val mode = displayToMode[selectedItem] ?: return
            writeSetting(preferenceKey, mode)
            isDirty = false
            syncCleanupModeToServer(mode)
        }
    }

    // Bester Versuch - schlaegt der Sync fehl, behaelt der Server den vorherigen Modus,
    // naechste erfolgreiche Aenderung gleicht ab. Kein Absturz oder Blockieren der lokalen Speicherung.
    private suspend fun syncCleanupModeToServer(mode: String) {
        withContext(Dispatchers.IO) {
            try {
                val endpoint = dataStore.data.map { it[ENDPOINT] }.first() ?: return@withContext
                if (endpoint.isEmpty()) return@withContext
                val url = endpoint.substringBefore("?") + "/cleanup-mode"
                val body = "{\"mode\":\"$mode\"}".toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(body).build()
                cleanupModeSyncClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w("MainActivity", "Cleanup-Modus-Sync: Server antwortete mit HTTP ${response.code}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MainActivity", "Cleanup-Modus-Sync fehlgeschlagen: ${e.message}")
            }
        }
    }

    private fun setupSettingItems() {
        setupSettingItemsDone = false
        // Model-Feld ist reine Anzeige - App hat keine Modellwahl mehr, das Cleanup-Modell
        // wird server-seitig festgelegt (GENERATE_MODEL in api/MulmAI/asr.mjs).
        findViewById<EditText>(R.id.field_model).setText(CURRENT_SERVER_MODEL)
        // Add setting items here to apply functions to them
        CoroutineScope(Dispatchers.Main).launch {
            val settingItems = arrayOf(
                SettingDropdown(R.id.spinner_auto_recording_start, AUTO_RECORDING_START, hashMapOf(
                    getString(R.string.settings_option_yes) to true,
                    getString(R.string.settings_option_no) to false,
                )),
                SettingDropdown(R.id.spinner_auto_switch_back, AUTO_SWITCH_BACK, hashMapOf(
                    getString(R.string.settings_option_yes) to true,
                    getString(R.string.settings_option_no) to false,
                ), false),
                SettingDropdown(R.id.spinner_add_trailing_space, ADD_TRAILING_SPACE, hashMapOf(
                    getString(R.string.settings_option_yes) to true,
                    getString(R.string.settings_option_no) to false,
                ), false),
                SettingCleanupMode(R.id.spinner_postprocessing, POSTPROCESSING, hashMapOf(
                    getString(R.string.settings_option_cleanup_off) to "off",
                    getString(R.string.settings_option_cleanup_light) to "light",
                    getString(R.string.settings_option_cleanup_heavy) to "heavy",
                ), "light"),
            )
            val btnApply: Button = findViewById(R.id.btn_settings_apply)
            btnApply.isEnabled = false
            btnApply.setOnClickListener {
                CoroutineScope(Dispatchers.Main).launch {
                    btnApply.isEnabled = false
                    for (settingItem in settingItems) {
                        settingItem.apply()
                    }
                    btnApply.isEnabled = false
                }
                Toast.makeText(this@MainActivity, R.string.successfully_set, Toast.LENGTH_SHORT).show()
            }
            settingItems.map { settingItem -> settingItem.setup() }.joinAll()
            setupSettingItemsDone = true
        }
    }
}
