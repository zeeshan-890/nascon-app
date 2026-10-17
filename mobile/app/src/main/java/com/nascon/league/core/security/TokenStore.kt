package com.nascon.league.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nascon.league.core.network.AppJson
import com.nascon.league.domain.model.Session

class TokenStore(private val context: Context) {
    private val memory = mutableMapOf<String, String>()
    private val prefs: SharedPreferences? by lazy { createPrefs() }

    private fun createPrefs(): SharedPreferences? {
        repeat(2) { attempt ->
            try {
                val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                return EncryptedSharedPreferences.create(
                    context,
                    FILE_NAME,
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Secure storage unavailable (attempt ${attempt + 1}): ${e.javaClass.simpleName}")
                if (attempt == 0) context.deleteSharedPreferences(FILE_NAME)
            }
        }
        return null
    }

    private fun get(key: String): String? = prefs?.getString(key, null) ?: memory[key]

    private fun put(key: String, value: String) {
        val p = prefs
        if (p != null) p.edit().putString(key, value).apply() else memory[key] = value
    }

    fun accessToken(): String? = get(ACCESS)
    fun refreshToken(): String? = get(REFRESH)
    fun expiresAtEpoch(): Long = get(EXPIRES)?.toLongOrNull() ?: 0L

    fun saveTokens(access: String, refresh: String, expiresAtEpoch: Long) {
        put(ACCESS, access)
        put(REFRESH, refresh)
        put(EXPIRES, expiresAtEpoch.toString())
    }

    fun saveSession(session: Session) {
        put(SESSION, AppJson.encodeToString(Session.serializer(), session))
    }

    fun loadSession(): Session? = try {
        get(SESSION)?.let { AppJson.decodeFromString(Session.serializer(), it) }
    } catch (e: Exception) {
        null
    }

    fun clear() {
        memory.clear()
        prefs?.edit()?.clear()?.apply()
    }

    private companion object {
        const val TAG = "TokenStore"
        const val FILE_NAME = "nascon_secure_prefs"
        const val ACCESS = "access_token"
        const val REFRESH = "refresh_token"
        const val EXPIRES = "expires_at"
        const val SESSION = "session"
    }
}
