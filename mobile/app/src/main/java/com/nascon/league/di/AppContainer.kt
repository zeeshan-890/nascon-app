package com.nascon.league.di

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.nascon.league.core.config.ServerConfigStore
import com.nascon.league.core.db.AppDatabase
import com.nascon.league.core.network.ApiFactory
import com.nascon.league.core.network.ConnState
import com.nascon.league.core.network.ConnectivityMonitor
import com.nascon.league.core.security.TokenStore
import com.nascon.league.data.api.LeagueApi
import com.nascon.league.data.fake.FakeLeagueApi
import com.nascon.league.data.repo.AuthRepository
import com.nascon.league.data.repo.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val configStore = ServerConfigStore(appContext)
    val tokenStore = TokenStore(appContext)
    val database: AppDatabase by lazy { AppDatabase.create(appContext) }
    val sessionRepository = SessionRepository(tokenStore)

    val apiFactory = ApiFactory(tokenStore) { sessionRepository.expire() }

    private val fakeApi = FakeLeagueApi()
    private val apiState: MutableStateFlow<LeagueApi> = MutableStateFlow(
        runBlocking { configStore.snapshot() }.let { if (it.useDemoData) fakeApi else apiFactory.create(it.baseUrl) },
    )
    val api: StateFlow<LeagueApi> get() = apiState
    val apiProvider: () -> LeagueApi = { apiState.value }

    val connectivity = ConnectivityMonitor(appContext, scope, apiProvider)
    val authRepository = AuthRepository(apiProvider, tokenStore, sessionRepository)

    init {
        scope.launch {
            configStore.config.collectLatest { cfg ->
                apiState.value = if (cfg.useDemoData) fakeApi else apiFactory.create(cfg.baseUrl)
                connectivity.refreshNow()
            }
        }
        scope.launch {
            connectivity.state.filter { it == ConnState.ONLINE }.collect { authRepository.refreshIfNeeded() }
        }
    }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
