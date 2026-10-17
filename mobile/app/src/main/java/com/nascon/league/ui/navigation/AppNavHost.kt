package com.nascon.league.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.nascon.league.ui.fixtures.FixturesScreen
import com.nascon.league.ui.match.MatchDetailScreen
import com.nascon.league.core.network.ConnState
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.domain.model.Session
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import com.nascon.league.ui.components.ArenaHeader
import com.nascon.league.ui.components.ConnectionPill
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.components.ConfirmDialog
import com.nascon.league.ui.components.ConnectivityBanner
import com.nascon.league.ui.home.HomeScreen
import com.nascon.league.ui.login.LoginScreen
import com.nascon.league.ui.placeholder.ComingSoonScreen
import com.nascon.league.ui.notifications.NotificationsScreen
import com.nascon.league.ui.orders.OrderDetailScreen
import com.nascon.league.ui.orders.OrdersScreen
import com.nascon.league.ui.player.MyHistoryScreen
import com.nascon.league.ui.player.PlayerHistoryScreen
import com.nascon.league.ui.player.PlayerProfileScreen
import com.nascon.league.ui.player.SquadScreen
import com.nascon.league.ui.profile.ProfileScreen
import com.nascon.league.ui.roster.RosterScreen
import com.nascon.league.ui.scoring.ScoringScreen
import com.nascon.league.ui.sync.SyncSnackbarEffect
import com.nascon.league.ui.sync.SyncStatusScreen
import com.nascon.league.ui.standings.StandingsScreen
import com.nascon.league.ui.club.ClubProfileScreen
import com.nascon.league.ui.club.ClubTabScreen
import com.nascon.league.ui.receipt.MyReceiptsScreen
import com.nascon.league.ui.receipt.ReceiptScreen
import com.nascon.league.ui.store.CartScreen
import com.nascon.league.ui.store.CheckoutScreen
import com.nascon.league.ui.store.OrderSuccessScreen
import com.nascon.league.ui.store.ProductDetailScreen
import com.nascon.league.ui.store.StoreScreen
import com.nascon.league.ui.settings.SettingsScreen
import com.nascon.league.ui.theme.NasconTheme
import com.nascon.league.data.repo.SessionEvent
import kotlinx.coroutines.launch

val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> { error("none") }

private fun titleFor(route: String?): String = when (baseRoute(route)) {
    Destinations.LOGIN -> "Sign in"
    Destinations.HOME -> "Home"
    Destinations.SETTINGS -> "Settings"
    Destinations.FIXTURES -> "Fixtures"
    Destinations.MATCH -> "Match details"
    Destinations.STANDINGS -> "Standings"
    Destinations.CLUB -> "Club"
    Destinations.CLUBS -> "Club"
    Destinations.CHECKOUT -> "Checkout"
    Destinations.ORDER_SUCCESS -> "Order placed"
    Destinations.RECEIPTS -> "My receipts"
    Destinations.STORE -> "Store"
    Destinations.PRODUCT -> "Product"
    Destinations.CART -> "Cart"
    Destinations.RECEIPT -> "Receipt"
    Destinations.ROSTER -> "Roster"
    Destinations.SCORING -> "Scoring"
    Destinations.SYNC -> "Sync status"
    Destinations.ADMIN -> "Admin overview"
    Destinations.PROFILE -> "Profile"
    Destinations.ORDERS -> "My orders"
    Destinations.ORDER_DETAIL -> "Order"
    Destinations.NOTIFICATIONS -> "Notifications"
    Destinations.PLAYER_BASE -> "Player"
    Destinations.PLAYER_HISTORY -> "Player history"
    Destinations.SQUAD -> "Squad"
    Destinations.MY_HISTORY -> "My history"
    else -> "Arena360"
}

private fun NavHostController.clearAndGoToLogin() {
    navigate(Destinations.LOGIN) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

@Composable
fun AppRoot() {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    NasconTheme(tenant = session?.tenant) {
        AppShell(session)
    }
}

@Composable
private fun AppShell(session: Session?) {
    val container = LocalAppContainer.current
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val startDestination = remember {
        if (container.sessionRepository.session.value != null) Destinations.HOME else Destinations.LOGIN
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val connState by container.connectivity.state.collectAsStateWithLifecycle(initialValue = ConnState.ONLINE)
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var sessionExpired by rememberSaveable { mutableStateOf(false) }
    val loggedIn = session != null
    val pendingFlow = remember(container) { container.pendingRepository.observePendingCount() }
    val pendingCount by pendingFlow.collectAsStateWithLifecycle(initialValue = 0)
    val isTab = isTabRoute(route)
    val unsyncedFlow = remember(container) { container.syncRepository.observePendingCount() }
    val unsynced by unsyncedFlow.collectAsStateWithLifecycle(initialValue = 0)

    LaunchedEffect(Unit) {
        container.sessionRepository.events.collect { event ->
            if (event is SessionEvent.Expired) {
                sessionExpired = true
                navController.clearAndGoToLogin()
            }
        }
    }

    fun navigateTab(target: String) {
        navController.navigate(target) {
            popUpTo(Destinations.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openSettings() {
        navController.navigate(Destinations.SETTINGS) { launchSingleTop = true }
    }

    fun clubRoute() = Destinations.CLUBS

    fun navigateFromHome(target: String) {
        val tabLike = target == Destinations.FIXTURES || target == Destinations.STANDINGS ||
            target == Destinations.STORE || target == clubRoute()
        if (tabLike) navigateTab(target)
        else navController.navigate(target) { launchSingleTop = true }
    }

    val headerController = remember { HeaderController() }
    DisposableEffect(navController) {
        val l = NavController.OnDestinationChangedListener { _, _, _ -> headerController.reset() }
        navController.addOnDestinationChangedListener(l)
        onDispose { navController.removeOnDestinationChangedListener(l) }
    }
    val isLogin = baseRoute(route) == Destinations.LOGIN
    val showHeader = !isLogin && !headerController.hidden && route != null
    val showBottomBar = loggedIn && isTab

    CompositionLocalProvider(
        LocalSnackbarHostState provides snackbar,
        LocalHeaderController provides headerController,
    ) {
        if (loggedIn) SyncSnackbarEffect()
        Scaffold(
            containerColor = Arena.colors.bg,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (showBottomBar) {
                    BottomBar(
                        currentRoute = route,
                        onTabClick = { tab ->
                            navigateTab(if (tab.route == Destinations.CLUB) clubRoute() else tab.route)
                        },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).background(Arena.colors.bg)) {
                if (showHeader) {
                    ArenaHeader(
                        title = titleFor(route),
                        subtitle = headerController.subtitle,
                        onBack = if (route != null && !isTab) ({ navController.navigateUp() }) else null,
                        tenant = session?.tenant,
                        trailing = {
                            TopBarActions(
                                route = route,
                                onSettings = ::openSettings,
                                session = session,
                                onNavigate = { target -> navController.navigate(target) { launchSingleTop = true } },
                                onSignOut = { confirmSignOut = true },
                            )
                            ConnectionPill(
                                state = connState,
                                pendingCount = pendingCount,
                                onClick = { navController.navigate(Destinations.SYNC) { launchSingleTop = true } },
                            )
                        },
                        extra = headerController.extra,
                        expanded = headerController.expanded,
                    )
                    ConnectivityBanner(
                        state = connState,
                        pendingCount = pendingCount,
                        onClick = { navController.navigate(Destinations.SYNC) { launchSingleTop = true } },
                        onOpenSettings = ::openSettings,
                    )
                }
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (showBottomBar || isLogin) Modifier
                            else Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                        ),
                ) {
                    composable(Destinations.LOGIN) {
                        LoginScreen(
                            sessionExpired = sessionExpired,
                            onLoggedIn = { roleLabel ->
                                sessionExpired = false
                                navController.navigate(Destinations.HOME) {
                                    popUpTo(Destinations.LOGIN) { inclusive = true }
                                    launchSingleTop = true
                                }
                                scope.launch { snackbar.showSnackbar("Welcome, $roleLabel") }
                            },
                            onOpenSettings = ::openSettings,
                        )
                    }
                    composable(Destinations.HOME) {
                        HomeScreen(onNavigate = ::navigateFromHome)
                    }
                    composable(Destinations.SETTINGS) {
                        SettingsScreen(
                            onSignedOut = { navController.clearAndGoToLogin() },
                            onOpenSync = { navController.navigate(Destinations.SYNC) { launchSingleTop = true } },
                        )
                    }
                    composable(
                        Destinations.FIXTURES_ROUTE,
                        arguments = listOf(
                            navArgument("filter") {
                                type = NavType.StringType
                                defaultValue = "all"
                            },
                        ),
                    ) {
                        FixturesScreen(
                            onOpenMatch = { id ->
                                navController.navigate(Destinations.match(id)) { launchSingleTop = true }
                            },
                        )
                    }
                    composable(
                        Destinations.MATCH,
                        arguments = listOf(navArgument("matchId") { type = NavType.StringType }),
                    ) {
                        MatchDetailScreen(
                            onBack = { navController.navigateUp() },
                            onOpenProduct = { id ->
                                navController.navigate(Destinations.product(id)) { launchSingleTop = true }
                            },
                            onScore = { id ->
                                navController.navigate(Destinations.scoring(id)) { launchSingleTop = true }
                            },
                        )
                    }
                    composable(Destinations.PROFILE) {
                        ProfileScreen(
                            onOpenSettings = ::openSettings,
                            onOpenSync = { navController.navigate(Destinations.SYNC) { launchSingleTop = true } },
                            onOpenOrders = { navController.navigate(Destinations.ORDERS) { launchSingleTop = true } },
                            onOpenNotifications = { navController.navigate(Destinations.NOTIFICATIONS) { launchSingleTop = true } },
                            onSignOut = { confirmSignOut = true },
                        )
                    }
                    composable(Destinations.ORDERS) {
                        OrdersScreen(
                            onOpenOrder = { id -> navController.navigate(Destinations.orderDetail(id)) { launchSingleTop = true } },
                            onBrowseStore = { navigateTab(Destinations.STORE) },
                        )
                    }
                    composable(
                        Destinations.ORDER_DETAIL,
                        arguments = listOf(navArgument("orderId") { type = NavType.StringType }),
                    ) { entry ->
                        OrderDetailScreen(
                            orderId = entry.arguments?.getString("orderId").orEmpty(),
                            onOpenReceipt = { id -> navController.navigate(Destinations.receipt(id)) { launchSingleTop = true } },
                        )
                    }
                    composable(Destinations.NOTIFICATIONS) { NotificationsScreen() }
                    composable(
                        Destinations.PLAYER,
                        arguments = listOf(
                            navArgument("playerId") { type = NavType.StringType },
                            navArgument("clubId") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                    ) { entry ->
                        PlayerProfileScreen(
                            playerId = entry.arguments?.getString("playerId").orEmpty(),
                            clubId = entry.arguments?.getString("clubId"),
                            onOpenHistory = { id -> navController.navigate(Destinations.playerHistory(id)) { launchSingleTop = true } },
                        )
                    }
                    composable(
                        Destinations.PLAYER_HISTORY,
                        arguments = listOf(navArgument("playerId") { type = NavType.StringType }),
                    ) { entry ->
                        PlayerHistoryScreen(playerId = entry.arguments?.getString("playerId").orEmpty())
                    }
                    composable(
                        Destinations.SQUAD,
                        arguments = listOf(navArgument("clubId") { type = NavType.StringType }),
                    ) { entry ->
                        val cid = entry.arguments?.getString("clubId").orEmpty()
                        SquadScreen(
                            clubId = cid,
                            onOpenPlayer = { pid -> navController.navigate(Destinations.player(pid, cid)) { launchSingleTop = true } },
                        )
                    }
                    composable(Destinations.MY_HISTORY) { MyHistoryScreen() }
                    composable(Destinations.STANDINGS) {
                        StandingsScreen(onViewFixtures = { navigateTab(Destinations.FIXTURES) })
                    }
                    composable(Destinations.CLUBS) {
                        ClubTabScreen(
                            onOpenClub = { id -> navController.navigate(Destinations.club(id)) { launchSingleTop = true } },
                            onManageRoster = { id -> navController.navigate(Destinations.roster(id)) { launchSingleTop = true } },
                            onOpenPlayer = { pid, cid -> navController.navigate(Destinations.player(pid, cid)) { launchSingleTop = true } },
                        )
                    }
                    composable(
                        Destinations.CLUB,
                        arguments = listOf(navArgument("clubId") { type = NavType.StringType }),
                    ) { entry ->
                        ClubProfileScreen(
                            clubId = entry.arguments?.getString("clubId").orEmpty(),
                            onManageRoster = { id -> navController.navigate(Destinations.roster(id)) { launchSingleTop = true } },
                            onOpenPlayer = { pid, cid -> navController.navigate(Destinations.player(pid, cid)) { launchSingleTop = true } },
                        )
                    }
                    composable(Destinations.STORE) {
                        StoreScreen(
                            onOpenProduct = { id -> navController.navigate(Destinations.product(id)) { launchSingleTop = true } },
                            onOpenCart = { navController.navigate(Destinations.CART) { launchSingleTop = true } },
                            onOpenReceipts = { navController.navigate(Destinations.RECEIPTS) { launchSingleTop = true } },
                        )
                    }
                    composable(
                        Destinations.PRODUCT,
                        arguments = listOf(navArgument("productId") { type = NavType.StringType }),
                    ) { entry ->
                        ProductDetailScreen(
                            productId = entry.arguments?.getString("productId").orEmpty(),
                            onOpenCart = { navController.navigate(Destinations.CART) { launchSingleTop = true } },
                        )
                    }
                    composable(Destinations.CART) {
                        CartScreen(
                            onCheckout = { navController.navigate(Destinations.CHECKOUT) { launchSingleTop = true } },
                            onBrowse = { navigateTab(Destinations.STORE) },
                        )
                    }
                    composable(Destinations.CHECKOUT) {
                        CheckoutScreen(
                            onOrderPlaced = { id ->
                                navController.navigate(Destinations.orderSuccess(id)) {
                                    popUpTo(Destinations.CART) { inclusive = true }
                                }
                            },
                            onBackToCart = { navController.popBackStack() },
                        )
                    }
                    composable(
                        Destinations.ORDER_SUCCESS,
                        arguments = listOf(navArgument("orderId") { type = NavType.StringType }),
                    ) { entry ->
                        OrderSuccessScreen(
                            orderId = entry.arguments?.getString("orderId").orEmpty(),
                            onViewReceipt = { id -> navController.navigate(Destinations.receipt(id)) { launchSingleTop = true } },
                            onContinueShopping = { navigateTab(Destinations.STORE) },
                        )
                    }
                    composable(Destinations.RECEIPTS) {
                        MyReceiptsScreen(
                            onOpenReceipt = { id -> navController.navigate(Destinations.receipt(id)) { launchSingleTop = true } },
                        )
                    }
                    composable(
                        Destinations.RECEIPT,
                        arguments = listOf(navArgument("orderId") { type = NavType.StringType }),
                    ) { entry ->
                        ReceiptScreen(orderId = entry.arguments?.getString("orderId").orEmpty())
                    }
                    composable(
                        Destinations.ROSTER,
                        arguments = listOf(navArgument("clubId") { type = NavType.StringType }),
                    ) { entry ->
                        RosterScreen(clubId = entry.arguments?.getString("clubId").orEmpty())
                    }
                    composable(
                        Destinations.SCORING,
                        arguments = listOf(navArgument("matchId") { type = NavType.StringType }),
                    ) { entry ->
                        ScoringScreen(matchId = entry.arguments?.getString("matchId").orEmpty())
                    }
                    composable(Destinations.SYNC) { SyncStatusScreen() }
                    listOf(
                        Destinations.ADMIN,
                    ).forEach { r ->
                        composable(r) { ComingSoonScreen(title = titleFor(r)) }
                    }
                }
            }
        }

        if (confirmSignOut) {
            ConfirmDialog(
                title = "Sign out?",
                message = when {
                    unsynced <= 0 -> androidx.compose.ui.res.stringResource(com.nascon.league.R.string.f10a_signout_message)
                    unsynced == 1 -> androidx.compose.ui.res.stringResource(com.nascon.league.R.string.f10a_unsynced_one)
                    else -> androidx.compose.ui.res.stringResource(com.nascon.league.R.string.f10a_unsynced_many, unsynced)
                },
                confirmLabel = "Sign out",
                onConfirm = {
                    confirmSignOut = false
                    scope.launch {
                        container.authRepository.logout()
                        navController.clearAndGoToLogin()
                    }
                },
                onDismiss = { confirmSignOut = false },
                destructive = true,
            )
        }
    }
}

@Composable
private fun RowScope.TopBarActions(
    route: String?,
    onSettings: () -> Unit,
    session: Session?,
    onNavigate: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    when (route) {
        Destinations.LOGIN -> IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Settings, contentDescription = "Server settings", tint = Color.White)
        }
        Destinations.HOME -> {
            var menuOpen by remember { mutableStateOf(false) }
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
            }
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Color.White)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val clubId = session?.user?.clubId
                val menuItems = buildList {
                    add("Profile" to Destinations.PROFILE)
                    add("My orders" to Destinations.ORDERS)
                    add("Notifications" to Destinations.NOTIFICATIONS)
                    if (session?.user?.role == com.nascon.league.domain.model.Role.PLAYER) {
                        if (clubId != null) add("Squad" to Destinations.squad(clubId))
                        add("My history" to Destinations.MY_HISTORY)
                    }
                }
                menuItems.forEach { (label, target) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            menuOpen = false
                            onNavigate(target)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Sign out") },
                    onClick = {
                        menuOpen = false
                        onSignOut()
                    },
                )
            }
        }
        else -> Unit
    }
}
