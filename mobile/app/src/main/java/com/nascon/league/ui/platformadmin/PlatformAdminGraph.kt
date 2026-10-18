package com.nascon.league.ui.platformadmin

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nascon.league.R
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.EmptyStateCard
import com.nascon.league.ui.navigation.Destinations
import com.nascon.league.ui.navigation.NewRoutes

/** Header title for every route this package registers (keyed by the exact route pattern). */
val platformAdminTitles: Map<String, String> = mapOf(
    NewRoutes.ADMIN_CLUBS to "All clubs",
    NewRoutes.ADMIN_CLUB_NEW to "New club",
    NewRoutes.ADMIN_SPORTS to "Sports",
    NewRoutes.ADMIN_SPORT_CONFIG to "Sport settings",
    NewRoutes.ADMIN_SCORING_EVENT to "Scoring event",
    NewRoutes.ADMIN_ACCESS to "Access rules",
    NewRoutes.ADMIN_AUDIT to "Audit log",
    NewRoutes.ADMIN_AUDIT_DETAIL to "Audit entry",
    NewRoutes.ADMIN_AUDIT_FILTER to "Filter log",
    NewRoutes.ADMIN_STATUS to "System status",
)

/** Only the super admin gets in; for anyone else the screen is replaced by a plain "no access" message. */
@Composable
private fun AdminGate(content: @Composable () -> Unit) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    if (canAdminister(session)) {
        content()
    } else {
        EmptyStateCard(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.pa_no_access_title),
            message = stringResource(R.string.pa_no_access_message),
        )
    }
}

/**
 * Registers G07-G16. G15 (audit filter) is a full screen route, so ADMIN_AUDIT_FILTER is registered here.
 * Entry points: ADMIN_CLUBS, ADMIN_SPORTS, ADMIN_ACCESS, ADMIN_AUDIT, ADMIN_STATUS (super admin only).
 */
fun NavGraphBuilder.platformAdminGraph(nav: NavHostController) {
    fun go(route: String) = nav.navigate(route) { launchSingleTop = true }
    val back: () -> Unit = { nav.popBackStack() }

    composable(NewRoutes.ADMIN_CLUBS) {
        AdminGate {
            AdminClubsScreen(
                onClub = { go(Destinations.club(it)) },
                onNew = { go(NewRoutes.ADMIN_CLUB_NEW) },
                onBack = back,
            )
        }
    }
    composable(NewRoutes.ADMIN_CLUB_NEW) {
        AdminGate {
            val container = LocalAppContainer.current
            val tenant = container.sessionRepository.session.value?.tenant?.tenantId.orEmpty()
            NewClubScreen(defaultTenant = tenant, onDone = back, onCancel = back)
        }
    }
    composable(NewRoutes.ADMIN_SPORTS) {
        AdminGate { AdminSportsScreen(onSport = { go(NewRoutes.adminSport(it)) }, onBack = back) }
    }
    composable(
        NewRoutes.ADMIN_SPORT_CONFIG,
        arguments = listOf(navArgument("sportId") { type = NavType.StringType }),
    ) { entry ->
        val sportId = entry.arguments?.getString("sportId").orEmpty()
        AdminGate {
            SportConfigScreen(
                sportId = sportId,
                onEvent = { key -> go(NewRoutes.adminScoringEvent(sportId, key)) },
                onBack = back,
            )
        }
    }
    composable(
        NewRoutes.ADMIN_SCORING_EVENT,
        arguments = listOf(
            navArgument("sportId") { type = NavType.StringType },
            navArgument("key") { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) { entry ->
        val sportId = entry.arguments?.getString("sportId").orEmpty()
        val key = entry.arguments?.getString("key")?.takeIf { it.isNotBlank() }
        AdminGate { ScoringEventScreen(sportId = sportId, eventKey = key, onDone = back, onCancel = back) }
    }
    composable(NewRoutes.ADMIN_ACCESS) { AdminGate { AccessScreen(onBack = back) } }
    composable(NewRoutes.ADMIN_AUDIT) {
        AdminGate {
            AuditScreen(
                onEntry = { go(NewRoutes.adminAuditDetail(it)) },
                onFilter = { go(NewRoutes.ADMIN_AUDIT_FILTER) },
                onBack = back,
            )
        }
    }
    composable(
        NewRoutes.ADMIN_AUDIT_DETAIL,
        arguments = listOf(navArgument("entryId") { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString("entryId").orEmpty()
        AdminGate { AuditDetailScreen(entryId = id, onBack = back) }
    }
    composable(NewRoutes.ADMIN_AUDIT_FILTER) {
        AdminGate { AuditFilterScreen(onDone = back, onCancel = back) }
    }
    composable(NewRoutes.ADMIN_STATUS) { AdminGate { SystemStatusScreen() } }
}
