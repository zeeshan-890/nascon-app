package com.nascon.league.core.config

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.configDataStore by preferencesDataStore(name = "server_config")

data class ServerConfig(val baseUrl: String, val useDemoData: Boolean)

class ServerConfigStore(context: Context) {
    private val store = context.applicationContext.configDataStore

    val baseUrl: Flow<String> = store.data.map { it[KEY_BASE_URL] ?: DEFAULT_BASE_URL }
    val useDemoData: Flow<Boolean> = store.data.map { it[KEY_DEMO] ?: true }
    val config: Flow<ServerConfig> = store.data.map {
        ServerConfig(it[KEY_BASE_URL] ?: DEFAULT_BASE_URL, it[KEY_DEMO] ?: true)
    }

    suspend fun snapshot(): ServerConfig = config.first()

    suspend fun deviceId(): String {
        store.data.first()[KEY_DEVICE_ID]?.let { return it }
        var created = ""
        store.edit { prefs ->
            val existing = prefs[KEY_DEVICE_ID]
            created = existing ?: UUID.randomUUID().toString()
            if (existing == null) prefs[KEY_DEVICE_ID] = created
        }
        return created
    }

    suspend fun setBaseUrl(raw: String): Boolean {
        val normalized = normalizeUrl(raw) ?: return false
        store.edit { it[KEY_BASE_URL] = normalized }
        return true
    }

    suspend fun setUseDemoData(value: Boolean) {
        store.edit { it[KEY_DEMO] = value }
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8080/"
        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_DEMO = booleanPreferencesKey("use_demo_data")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")

        fun normalizeUrl(raw: String): String? {
            val text = raw.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            val withScheme = when {
                text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) -> text
                text.contains("://") -> return null
                else -> "http://$text"
            }
            val host = withScheme.substringAfter("://").substringBefore('/')
            if (host.isEmpty() || host.startsWith(":")) return null
            return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
        }
    }
}
