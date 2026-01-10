package com.clawdbot.ar.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Secure preferences storage using EncryptedSharedPreferences.
 */
class SecurePrefs(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "clawdar_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Instance ID - unique identifier for this device
    private val _instanceId = MutableStateFlow(
        prefs.getString(KEY_INSTANCE_ID, null) ?: generateInstanceId()
    )
    val instanceId: StateFlow<String> = _instanceId.asStateFlow()

    // Display name for this device
    private val _displayName = MutableStateFlow(
        prefs.getString(KEY_DISPLAY_NAME, null) ?: generateDisplayName()
    )
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    // Bridge authentication token
    private val _bridgeToken = MutableStateFlow(
        prefs.getString(KEY_BRIDGE_TOKEN, null)
    )
    val bridgeToken: StateFlow<String?> = _bridgeToken.asStateFlow()

    // Manual host configuration
    private val _manualHost = MutableStateFlow(
        prefs.getString(KEY_MANUAL_HOST, null)
    )
    val manualHost: StateFlow<String?> = _manualHost.asStateFlow()

    private val _manualPort = MutableStateFlow(
        prefs.getInt(KEY_MANUAL_PORT, 18790)
    )
    val manualPort: StateFlow<Int> = _manualPort.asStateFlow()

    private fun generateInstanceId(): String {
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTANCE_ID, id).apply()
        return id
    }

    private fun generateDisplayName(): String {
        val name = "${Build.MANUFACTURER} ${Build.MODEL} AR"
        prefs.edit().putString(KEY_DISPLAY_NAME, name).apply()
        return name
    }

    fun setDisplayName(name: String) {
        prefs.edit().putString(KEY_DISPLAY_NAME, name).apply()
        _displayName.value = name
    }

    fun setBridgeToken(token: String?) {
        if (token != null) {
            prefs.edit().putString(KEY_BRIDGE_TOKEN, token).apply()
        } else {
            prefs.edit().remove(KEY_BRIDGE_TOKEN).apply()
        }
        _bridgeToken.value = token
    }

    fun setManualHost(host: String?, port: Int) {
        if (host != null) {
            prefs.edit()
                .putString(KEY_MANUAL_HOST, host)
                .putInt(KEY_MANUAL_PORT, port)
                .apply()
        } else {
            prefs.edit()
                .remove(KEY_MANUAL_HOST)
                .remove(KEY_MANUAL_PORT)
                .apply()
        }
        _manualHost.value = host
        _manualPort.value = port
    }

    fun clearToken() {
        setBridgeToken(null)
    }

    // Chat session key - persisted to maintain conversation continuity
    fun getChatSessionKey(): String? {
        return prefs.getString(KEY_CHAT_SESSION, null)
    }

    fun setChatSessionKey(key: String) {
        prefs.edit().putString(KEY_CHAT_SESSION, key).apply()
    }

    fun clearChatSession() {
        prefs.edit().remove(KEY_CHAT_SESSION).apply()
    }

    companion object {
        private const val KEY_INSTANCE_ID = "instance_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_BRIDGE_TOKEN = "bridge_token"
        private const val KEY_MANUAL_HOST = "manual_host"
        private const val KEY_MANUAL_PORT = "manual_port"
        private const val KEY_CHAT_SESSION = "chat_session_key"
    }
}
