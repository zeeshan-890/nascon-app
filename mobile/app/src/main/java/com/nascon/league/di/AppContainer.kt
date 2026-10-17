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
import com.nascon.league.data.realtime.FakeRealtime
import com.nascon.league.data.realtime.OkHttpRealtimeClient
import com.nascon.league.data.realtime.RealtimeClient
import com.nascon.league.data.realtime.SwitchingRealtimeClient
import com.nascon.league.data.repo.AuthRepository
import com.nascon.league.data.repo.CacheManager
import com.nascon.league.data.repo.ClubRepository
import com.nascon.league.data.repo.ClubsListRepository
import com.nascon.league.data.receipts.ReceiptStore
import com.nascon.league.data.store.CartStore
import com.nascon.league.data.store.CheckoutAttemptStore
import com.nascon.league.data.repo.FixturesRepository
import com.nascon.league.data.repo.LeaguesRepository
import com.nascon.league.data.repo.MatchRepository
import com.nascon.league.data.repo.OrderRepository
import com.nascon.league.data.repo.PendingActionRepository
import com.nascon.league.data.repo.PlayerHistoryRepository
import com.nascon.league.data.repo.ProductRepository
import com.nascon.league.data.repo.RefreshDeduper
import com.nascon.league.data.repo.SportRepository
import com.nascon.league.data.repo.StandingsRepository
import com.nascon.league.data.repo.SessionRepository
import com.nascon.league.data.sync.AppSyncEnvironment
import com.nascon.league.data.sync.RepositoryCacheRefresher
import com.nascon.league.data.sync.RetrofitSyncApi
import com.nascon.league.data.sync.RoomMatchCache
import com.nascon.league.data.sync.RoomSyncStore
import com.nascon.league.data.sync.SyncRepository
import com.nascon.league.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val configStore = ServerConfigStore(appContext)
    val tokenStore = TokenStore(appContext)
    val database: AppDatabase by lazy { AppDatabase.create(appContext) }
    val sessionRepository = SessionRepository(tokenStore)

    val apiFactory = ApiFactory(tokenStore) { sessionRepository.expire() }

    @Volatile private var demoMode = true
    @Volatile private var currentBaseUrl = ServerConfigStore.DEFAULT_BASE_URL
    private val fakeApi = FakeLeagueApi { connectivity.isNetworkAvailable() }
    private val apiState: MutableStateFlow<LeagueApi> = MutableStateFlow(
        runBlocking { configStore.snapshot() }.let {
            demoMode = it.useDemoData
            currentBaseUrl = it.baseUrl
            if (it.useDemoData) fakeApi else apiFactory.create(it.baseUrl)
        },
    )
    val api: StateFlow<LeagueApi> get() = apiState
    val apiProvider: () -> LeagueApi = { apiState.value }

    val connectivity = ConnectivityMonitor(appContext, scope, apiProvider)
    private val currentUserId: () -> String? = { sessionRepository.session.value?.user?.userId }
    private val isOnline: () -> Boolean = { connectivity.state.value != ConnState.OFFLINE }
    private val deduper = RefreshDeduper(scope)

    val cacheManager = CacheManager(database.cacheDao())
    val authRepository = AuthRepository(apiProvider, tokenStore, sessionRepository, cacheManager)

    val leaguesRepository = LeaguesRepository(database.leagueDao(), apiProvider, deduper, isOnline)
    val fixturesRepository = FixturesRepository(database.fixtureDao(), apiProvider, deduper, isOnline)
    val matchRepository = MatchRepository(database.matchDao(), apiProvider, deduper, isOnline)
    val standingsRepository = StandingsRepository(database.standingsDao(), apiProvider, deduper, isOnline)
    val clubRepository = ClubRepository(database.clubDao(), apiProvider, deduper, isOnline)
    val sportRepository = SportRepository(database.sportDao(), apiProvider, deduper, isOnline)
    val productRepository = ProductRepository(database.productDao(), apiProvider, deduper, isOnline)
    val clubsListRepository = ClubsListRepository(database.cacheDao(), apiProvider, deduper, isOnline)
    val playerHistoryRepository = PlayerHistoryRepository(database.cacheDao(), apiProvider, deduper, isOnline)
    val orderRepository = OrderRepository(database.cacheDao(), apiProvider, deduper, isOnline)
    val cartStore = CartStore(appContext, currentUserId)
    val checkoutAttemptStore = CheckoutAttemptStore(appContext, currentUserId)
    val receiptStore = ReceiptStore(appContext, currentUserId, apiProvider)
    val pendingRepository = PendingActionRepository(database.pendingActionDao(), currentUserId)

    val syncRepository: SyncRepository = SyncRepository(
        store = RoomSyncStore(database),
        matches = RoomMatchCache(database.matchDao()),
        api = RetrofitSyncApi(apiProvider),
        env = AppSyncEnvironment(sessionRepository, tokenStore, configStore, authRepository, isOnline),
        refresher = RepositoryCacheRefresher(
            leaguesRepository, fixturesRepository, matchRepository, standingsRepository,
            clubRepository, database.clubDao(),
        ),
        scheduler = SyncScheduler(appContext),
        scope = scope,
    )

    val realtime: RealtimeClient =SwitchingRealtimeClient(
        useFake = { demoMode },
        real = OkHttpRealtimeClient(
            baseUrlProvider = { currentBaseUrl },
            tokenProvider = { tokenStore.accessToken() },
            scope = scope,
            tryRefreshToken = {
                authRepository.refreshIfNeeded()
                tokenStore.accessToken() != null
            },
        ),
        fake = FakeRealtime(scope, fakeApi),
    )

    private val _refreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits whenever connectivity transitions to ONLINE; active screens collect it to refresh. */
    val refreshSignal: SharedFlow<Unit> = _refreshSignal

    init {
        // Crash safety: rows left SYNCING by a killed process go back to PENDING, then sync if online.
        scope.launch {
            runCatching { syncRepository.resetInterrupted() }
            runCatching { syncRepository.onOnline() }
        }
        scope.launch {
            var previous: ConnState? = null
            connectivity.state.collect { now ->
                if (now == ConnState.ONLINE && previous != ConnState.ONLINE) {
                    runCatching { syncRepository.onOnline() }
                }
                previous = now
            }
        }
        scope.launch {
            configStore.config.collectLatest { cfg ->
                demoMode = cfg.useDemoData
                currentBaseUrl = cfg.baseUrl
                apiState.value = if (cfg.useDemoData) fakeApi else apiFactory.create(cfg.baseUrl)
                connectivity.refreshNow()
            }
        }
        scope.launch {
            connectivity.state.filter { it == ConnState.ONLINE }.collect { authRepository.refreshIfNeeded() }
        }
        scope.launch {
            var previous: ConnState? = null
            connectivity.state.collect { now ->
                if (now == ConnState.ONLINE && previous != ConnState.ONLINE) _refreshSignal.tryEmit(Unit)
                previous = now
            }
        }
    }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
