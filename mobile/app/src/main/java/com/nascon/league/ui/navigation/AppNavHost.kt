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
import com.nascon.league.ui.components.AppTopBar
import com.nascon.league.ui.components.ConfirmDialog
import com.nascon.league.ui.components.ConnectivityBanner
import com.nascon.league.ui.home.HomeScreen
import com.nascon.league.ui.login.LoginScreen
import com.nascon.league.ui.placeholder.ComingSoonScreen
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
    Destinations.STORE -> "Store"
    Destinations.PRODUCT -> "Product"
    Destinations.CART -> "Cart"
    Destinations.RECEIPT -> "Receipt"
    Destinations.ROSTER -> "Roster"
    Destinations.SCORING -> "Scoring"
    Destinations.SYNC -> "Sync status"
    Destinations.ADMIN -> "Admin overview"
    else -> "NaSCon League"
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
    val loggedIn = session != null
    val pendingFlow = remember(container) { container.pendingRepository.observePendingCount() }
    val pendingCount by pendingFlow.collectAsStateWithLifecycle(initialValue = 0)
    val isTab = isTabRoute(route)

    LaunchedEffect(Unit) {
        container.sessionRepository.events.collect { event ->
            if (event is SessionEvent.Expired) {
                navController.clearAndGoToLogin()
                snackbar.showSnackbar("Your session expired. Please sign in again.")
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

    fun clubRoute() = Destinations.club(session?.user?.clubId ?: Destinations.DEFAULT_CLUB_ID)

    fun navigateFromHome(target: String) {
        val tabLike = target == Destinations.FIXTURES || target == Destinations.STANDINGS ||
            target == Destinations.STORE || target == clubRoute()
        if (tabLike) navigateTab(target)
        else navController.navigate(target) { launchSingleTop = true }
    }

    CompositionLocalProvider(LocalSnackbarHostState provides snackbar) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (loggedIn && isTab) {
                    BottomBar(
                        currentRoute = route,
                        onTabClick = { tab ->
                            navigateTab(if (tab.route == Destinations.CLUB) clubRoute() else tab.route)
                        },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                AppTopBar(
                    title = titleFor(route),
                    showBack = route != null && route != Destinations.LOGIN && !isTab,
                    onBack = { navController.navigateUp() },
                    tenant = if (route == Destinations.LOGIN) null else session?.tenant,
                    actions = { TopBarActions(route, ::openSettings) { confirmSignOut = true } },
                )
                ConnectivityBanner(
                    state = connState,
                    pendingCount = pendingCount,
                    onClick = { navController.navigate(Destinations.SYNC) { launchSingleTop = true } },
                    onOpenSettings = ::openSettings,
                )
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier.weight(1f),
                ) {
                    composable(Destinations.LOGIN) {
                        LoginScreen(
                            onLoggedIn = { roleLabel ->
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
                        SettingsScreen(onSignedOut = { navController.clearAndGoToLogin() })
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
                    listOf(
                        Destinations.STANDINGS,
                        Destinations.CLUB, Destinations.STORE, Destinations.PRODUCT,
                        Destinations.CART, Destinations.RECEIPT, Destinations.ROSTER,
                        Destinations.SCORING, Destinations.SYNC, Destinations.ADMIN,
                    ).forEach { r ->
                        composable(r) { ComingSoonScreen(title = titleFor(r)) }
                    }
                }
            }
        }

        if (confirmSignOut) {
            ConfirmDialog(
                title = "Sign out?",
                message = "Sign out? Pending offline actions stay on this device.",
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
    onSignOut: () -> Unit,
) {
    when (route) {
        Destinations.LOGIN -> IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Server settings")
        }
        Destinations.HOME -> {
            var menuOpen by remember { mutableStateOf(false) }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
