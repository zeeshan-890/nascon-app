package com.nascon.league.di

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.nascon.league.core.config.ServerConfigStore
import com.nascon.league.matchlive.asMatchEventSource
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
    val sessionRepository = SessionRepository(tokenStore).also { it.ensureSession() }

    /** Links received by the activity wait here until the NavHost can open them. [deepLinkSignal] wakes the shell. */
    val deepLinks = com.nascon.league.deeplink.PendingDeepLink()
    val deepLinkSignal = kotlinx.coroutines.flow.MutableStateFlow(0)

    val apiFactory = ApiFactory(tokenStore) { sessionRepository.expire() }

    @Volatile private var demoMode = false
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

    /** True while the app runs on built-in demo data instead of a real server. */
    val isDemoMode: Boolean get() = demoMode

    /** Whether the device currently has a network (fakes use this to mimic a real offline call). */
    fun hasNetwork(): Boolean = connectivity.isNetworkAvailable()

    /**
     * Resolves an extra API interface: the demo fake in demo mode, otherwise a real authenticated Retrofit
     * service for the configured server. [fake] receives a hasNetwork provider and must return a long-lived instance.
     */
    fun <T : Any> service(service: Class<T>, fake: (hasNetwork: () -> Boolean) -> T): T =
        if (demoMode) fake { hasNetwork() } else apiFactory.createService(currentBaseUrl, service)

    val connectivity = ConnectivityMonitor(appContext, scope, apiProvider)

    // ---- Part II: live match screens ----
    private val offlineMatchLiveApi = object : com.nascon.league.matchlive.MatchLiveApi {
        private fun off(): Nothing = throw java.io.IOException("Live match data is not available in demo mode")
        override suspend fun score(matchId: String) = off()
        override suspend fun match(matchId: String) = off()
        override suspend fun chatMessages(roomId: String, afterSequence: Long?) = off()
        override suspend fun sendChat(body: kotlinx.serialization.json.JsonObject) = off()
    }
    private fun matchLiveApi(): com.nascon.league.matchlive.MatchLiveApi =
        service(com.nascon.league.matchlive.MatchLiveApi::class.java) { offlineMatchLiveApi }

    val matchLiveRepository: com.nascon.league.matchlive.MatchLiveRepository by lazy {
        com.nascon.league.matchlive.RemoteMatchLiveRepository(api = ::matchLiveApi, tokenProvider = { tokenStore.accessToken() })
    }

    val guestCart: com.nascon.league.matchlive.GuestCart by lazy {
        com.nascon.league.data.cart.RoomGuestCart(database.guestCartDao()) { tokenStore.accessToken() }
    }

    // ---- Part II: co-stream, progressive sign-in, cart merge ----
    private val offlineCoStreamApi = object : com.nascon.league.costream.CoStreamApi {
        override suspend fun session(id: String): kotlinx.serialization.json.JsonObject =
            throw java.io.IOException("Co-stream is not available in demo mode")
    }
    val coStreamRepository: com.nascon.league.costream.CoStreamRepository by lazy {
        com.nascon.league.costream.RemoteCoStreamRepository(
            api = { service(com.nascon.league.costream.CoStreamApi::class.java) { offlineCoStreamApi } },
        )
    }

    /** Where the user was when the sign-in sheet opened, so they return there after login. */
    val signInOrigin = com.nascon.league.ui.guest.SignInOriginStore()
    val signInSheetVisible = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val mergePrefs by lazy { appContext.getSharedPreferences("guest_merge", android.content.Context.MODE_PRIVATE) }

    /** The guest token whose cart is waiting to be merged. It survives the login (which replaces the active token) and a process kill. */
    var stagedGuestToken: String?
        get() = mergePrefs.getString("token", null)
        set(value) {
            mergePrefs.edit().apply { if (value == null) remove("token") else putString("token", value) }.apply()
        }

    private val offlineCartMergeApi = object : com.nascon.league.cartmerge.CartMergeApi {
        override suspend fun merge(body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject =
            throw java.io.IOException("Cart merge is not available in demo mode")
    }
    val cartMergeRepository: com.nascon.league.cartmerge.CartMergeRepository by lazy {
        com.nascon.league.cartmerge.RemoteCartMergeRepository(
            api = { service(com.nascon.league.cartmerge.CartMergeApi::class.java) { offlineCartMergeApi } },
        )
    }

    /** A merge machine over the STAGED guest cart (rows keyed by the guest token), not the active token. */
    fun newCartMergeMachine(): com.nascon.league.cartmerge.CartMergeMachine {
        val staged = com.nascon.league.data.cart.RoomGuestCart(database.guestCartDao()) { stagedGuestToken }
        return com.nascon.league.cartmerge.CartMergeMachine(
            repo = cartMergeRepository,
            cart = staged,
            guestTokenProvider = { stagedGuestToken },
            snapshotLines = { staged.snapshot() },
        )
    }

    /** Opens the progressive sign-in sheet and remembers the guest token and the screen to come back to. */
    fun openSignInSheet(originRoute: String?) {
        val token = tokenStore.accessToken()
        if (com.nascon.league.data.repo.GuestSession.isGuestToken(token)) stagedGuestToken = token
        signInOrigin.record(originRoute, null)
        signInSheetVisible.value = true
    }

    private val offlineGuestCheckoutApi = object : com.nascon.league.cartmerge.GuestCheckoutApi {
        override suspend fun checkout(body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject =
            throw java.io.IOException("Checkout is not available in demo mode")
    }

    /** The protected write for a guest cart. Returns the failure so the caller can open the sheet on 403. */
    suspend fun guestCheckoutAttempt(lines: List<com.nascon.league.matchlive.CartLine>): com.nascon.league.core.network.NetworkResult<kotlinx.serialization.json.JsonObject> {
        val api = service(com.nascon.league.cartmerge.GuestCheckoutApi::class.java) { offlineGuestCheckoutApi }
        val body = kotlinx.serialization.json.buildJsonObject {
            put("order_id", kotlinx.serialization.json.JsonPrimitive("guest-" + java.util.UUID.randomUUID().toString().take(12)))
            put("customer_id", kotlinx.serialization.json.JsonPrimitive("guest"))
            put("items", kotlinx.serialization.json.buildJsonArray {
                lines.forEach { l ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("variant_id", kotlinx.serialization.json.JsonPrimitive(l.variantId))
                        put("quantity", kotlinx.serialization.json.JsonPrimitive(l.quantity))
                    })
                }
            })
            put("discount_code", kotlinx.serialization.json.JsonNull)
            put("payment", kotlinx.serialization.json.buildJsonObject { put("token", kotlinx.serialization.json.JsonPrimitive("tok_declined")) })
        }
        return com.nascon.league.core.network.safeCall { api.checkout(body) }
    }

    private val realtimeHttp: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .addInterceptor(com.nascon.league.core.network.CleartextGuardInterceptor())
            .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** One resilient stream per screen: WebSocket, then SSE, then polling the unauthenticated score route. */
    fun newMatchEventSource(): com.nascon.league.matchlive.MatchEventSource {
        val base = { currentBaseUrl }
        val token = { tokenStore.accessToken() }
        val transports = listOf(
            com.nascon.league.data.realtime.WebSocketTransport(base, token, realtimeHttp),
            com.nascon.league.data.realtime.SseTransport(base, token, realtimeHttp),
            com.nascon.league.data.realtime.PollingTransport(fetch = { id ->
                com.nascon.league.data.realtime.ScorePollMapper.fromScoreJson(matchLiveApi().score(id))
            }),
        )
        return com.nascon.league.data.realtime.ResilientMatchStream(transports = transports, resync = { })
            .asMatchEventSource()
    }

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
