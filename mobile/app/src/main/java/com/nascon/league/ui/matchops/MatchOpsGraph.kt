package com.nascon.league.ui.matchops

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nascon.league.ui.navigation.NewRoutes

/** Match operations for organizers and super admins: status, reschedule (with conflicts) and pinned products. */
fun NavGraphBuilder.matchOpsGraph(nav: NavHostController) {
    val args = listOf(navArgument("matchId") { type = NavType.StringType })
    composable(NewRoutes.MATCH_ADMIN, arguments = args) {
        MatchAdminScreen(
            onBack = { nav.navigateUp() },
            onReschedule = { id -> nav.navigate(NewRoutes.matchReschedule(id)) { launchSingleTop = true } },
            onPins = { id -> nav.navigate(NewRoutes.matchPins(id)) { launchSingleTop = true } },
        )
    }
    composable(NewRoutes.MATCH_RESCHEDULE, arguments = args) {
        MatchRescheduleScreen(onBack = { nav.navigateUp() }, onDone = { nav.popBackStack() })
    }
    composable(NewRoutes.MATCH_PINS, arguments = args) {
        MatchPinsScreen(onBack = { nav.navigateUp() })
    }
}

val matchOpsTitles: Map<String, String> = mapOf(
    NewRoutes.MATCH_ADMIN to "Match admin",
    NewRoutes.MATCH_RESCHEDULE to "Reschedule",
    NewRoutes.MATCH_PINS to "Pin to match",
)
