package com.bitchat.android.calls

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Manages call-related settings: TURN server configuration, auto-reconnect behavior,
 * and other call preferences.
 */
class CallSettingsManager private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "bitchat_call_settings"
        
        // TURN server settings
        private const val KEY_TURN_ENABLED = "turn_enabled"
        private const val KEY_TURN_SERVER_URI = "turn_server_uri"
        private const val KEY_TURN_USERNAME = "turn_username"
        private const val KEY_TURN_PASSWORD = "turn_password"
        
        // Auto-reconnect settings
        private const val KEY_AUTO_RECONNECT_ENABLED = "auto_reconnect_enabled"
        private const val KEY_MAX_RECONNECT_ATTEMPTS = "max_reconnect_attempts"
        private const val KEY_RECONNECT_BASE_DELAY_MS = "reconnect_base_delay_ms"
        private const val KEY_RECONNECT_MAX_DELAY_MS = "reconnect_max_delay_ms"
        
        // Defaults
        private const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 5
        private const val DEFAULT_RECONNECT_BASE_DELAY_MS = 1000L
        private const val DEFAULT_RECONNECT_MAX_DELAY_MS = 30000L

        @Volatile
        private var INSTANCE: CallSettingsManager? = null

        fun getInstance(context: Context): CallSettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CallSettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ──────────────────────────── TURN Server Settings ────────────────────────────

    var isTurnEnabled: Boolean
        get() = prefs.getBoolean(KEY_TURN_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_TURN_ENABLED, value) }

    var turnServerUri: String
        get() = prefs.getString(KEY_TURN_SERVER_URI, "") ?: ""
        set(value) = prefs.edit { putString(KEY_TURN_SERVER_URI, value) }

    var turnUsername: String
        get() = prefs.getString(KEY_TURN_USERNAME, "") ?: ""
        set(value) = prefs.edit { putString(KEY_TURN_USERNAME, value) }

    var turnPassword: String
        get() = prefs.getString(KEY_TURN_PASSWORD, "") ?: ""
        set(value) = prefs.edit { putString(KEY_TURN_PASSWORD, value) }

    /**
     * Returns true if TURN is enabled AND all required fields are populated.
     */
    fun isTurnConfigured(): Boolean {
        return isTurnEnabled &&
                turnServerUri.isNotBlank() &&
                turnUsername.isNotBlank() &&
                turnPassword.isNotBlank()
    }

    // ──────────────────────────── Auto-Reconnect Settings ────────────────────────────

    var isAutoReconnectEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_RECONNECT_ENABLED, value) }

    var maxReconnectAttempts: Int
        get() = prefs.getInt(KEY_MAX_RECONNECT_ATTEMPTS, DEFAULT_MAX_RECONNECT_ATTEMPTS)
        set(value) = prefs.edit { putInt(KEY_MAX_RECONNECT_ATTEMPTS, value) }

    var reconnectBaseDelayMs: Long
        get() = prefs.getLong(KEY_RECONNECT_BASE_DELAY_MS, DEFAULT_RECONNECT_BASE_DELAY_MS)
        set(value) = prefs.edit { putLong(KEY_RECONNECT_BASE_DELAY_MS, value) }

    var reconnectMaxDelayMs: Long
        get() = prefs.getLong(KEY_RECONNECT_MAX_DELAY_MS, DEFAULT_RECONNECT_MAX_DELAY_MS)
        set(value) = prefs.edit { putLong(KEY_RECONNECT_MAX_DELAY_MS, value) }

    /**
     * Calculate delay for a given reconnect attempt using exponential backoff with jitter.
     * 
     * Formula: min(baseDelay * 2^(attempt-1), maxDelay) + random(0, baseDelay/2)
     * 
     * @param attempt The attempt number (1-based)
     * @return Delay in milliseconds before the next reconnect attempt
     */
    fun getReconnectDelay(attempt: Int): Long {
        if (attempt <= 0) return reconnectBaseDelayMs
        
        val exponentialDelay = reconnectBaseDelayMs * (1L shl (attempt - 1).coerceAtMost(10))
        val cappedDelay = minOf(exponentialDelay, reconnectMaxDelayMs)
        
        // Add jitter: random value between 0 and baseDelay/2
        val jitter = (Math.random() * reconnectBaseDelayMs / 2).toLong()
        
        return cappedDelay + jitter
    }
}
