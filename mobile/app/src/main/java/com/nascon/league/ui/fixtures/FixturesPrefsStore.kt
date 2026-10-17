package com.nascon.league.ui.fixtures

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.fixturesDataStore: DataStore<Preferences> by preferencesDataStore(name = "fixtures_prefs")

/** Remembers the last chosen league per signed-in user. */
class FixturesPrefsStore(context: Context) {
    private val store = context.applicationContext.fixturesDataStore

    private fun key(userId: String) = stringPreferencesKey("league_$userId")

    fun observeLeague(userId: String): Flow<String?> = store.data.map { it[key(userId)] }

    suspend fun saveLeague(userId: String, leagueId: String) {
        store.edit { it[key(userId)] = leagueId }
    }
}
