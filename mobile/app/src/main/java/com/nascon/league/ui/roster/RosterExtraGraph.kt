package com.nascon.league.ui.roster

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nascon.league.ui.navigation.NewRoutes
import com.nascon.league.ui.player.MyStatsScreen

fun NavGraphBuilder.rosterExtraGraph(nav: NavHostController) {
    composable(
        NewRoutes.ROSTER_ADD,
        arguments = listOf(navArgument("clubId") { type = NavType.StringType }),
    ) { entry ->
        AddPlayerScreen(
            clubId = entry.arguments?.getString("clubId").orEmpty(),
            onDone = { nav.popBackStack() },
        )
    }
    composable(NewRoutes.MY_STATS) {
        MyStatsScreen(onBack = { nav.popBackStack() })
    }
}

val rosterExtraTitles: Map<String, String> = mapOf(
    NewRoutes.ROSTER_ADD to "Add player",
    NewRoutes.MY_STATS to "My stats",
)
