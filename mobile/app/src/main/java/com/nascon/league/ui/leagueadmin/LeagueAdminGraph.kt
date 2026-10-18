package com.nascon.league.ui.leagueadmin

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nascon.league.R
import com.nascon.league.ui.navigation.Destinations
import com.nascon.league.ui.navigation.NewRoutes

/**
 * Header title per route pattern. The values are string resource ids resolved with [leagueAdminTitle];
 * [leagueAdminTitles] holds the English text for callers that need a plain map.
 */
val leagueAdminTitles: Map<String, String> = mapOf(
    NewRoutes.LEAGUE_ADMIN_LIST to "Leagues",
    NewRoutes.LEAGUE_ADMIN_DETAIL to "League",
    NewRoutes.LEAGUE_FORM to "League",
    NewRoutes.SEASONS to "Seasons",
    NewRoutes.SEASON_FORM to "New season",
    NewRoutes.DIVISIONS to "Divisions",
    NewRoutes.TEAMS to "Teams",
    NewRoutes.PROMOTION to "Promotion setup",
    NewRoutes.STANDINGS_RULES to "Standings rules",
)

/** Localised title for a route, or null when the route is not one of ours. */
@Composable
fun leagueAdminTitle(route: String, isEdit: Boolean = false): String? = when (route) {
    NewRoutes.LEAGUE_ADMIN_LIST -> stringResource(R.string.la_title_leagues)
    NewRoutes.LEAGUE_ADMIN_DETAIL -> stringResource(R.string.la_title_league)
    NewRoutes.LEAGUE_FORM -> stringResource(if (isEdit) R.string.la_title_edit_league else R.string.la_title_new_league)
    NewRoutes.SEASONS -> stringResource(R.string.la_title_seasons)
    NewRoutes.SEASON_FORM -> stringResource(R.string.la_title_new_season)
    NewRoutes.DIVISIONS -> stringResource(R.string.la_title_divisions)
    NewRoutes.TEAMS -> stringResource(R.string.la_title_teams)
    NewRoutes.PROMOTION -> stringResource(R.string.la_title_promotion)
    NewRoutes.STANDINGS_RULES -> stringResource(R.string.la_title_rules)
    else -> null
}

private val leagueIdArg = listOf(navArgument("leagueId") { type = NavType.StringType })

fun NavGraphBuilder.leagueAdminGraph(nav: NavHostController) {
    fun go(route: String) = nav.navigate(route) { launchSingleTop = true }

    composable(NewRoutes.LEAGUE_ADMIN_LIST) {
        LeagueListScreen(
            onOpen = { go(NewRoutes.leagueAdminDetail(it)) },
            onNew = { go(NewRoutes.leagueForm()) },
            onBack = { nav.navigateUp() },
        )
    }
    composable(NewRoutes.LEAGUE_ADMIN_DETAIL, arguments = leagueIdArg) { e ->
        val id = e.arguments?.getString("leagueId").orEmpty()
        LeagueDetailScreen(
            leagueId = id,
            onBack = { nav.navigateUp() },
            onEdit = { go(NewRoutes.leagueForm(id)) },
            onGenerator = { go(NewRoutes.genFormat(id)) },
            onBracket = { go(NewRoutes.gen(id, "bracket/single")) },
            onStandingsRules = { go(NewRoutes.standingsRules(id)) },
            onTeams = { go(NewRoutes.teams(id)) },
            onPromotion = { go(NewRoutes.promotion(id)) },
            onSeasons = { go(NewRoutes.seasons(id)) },
            onDivisions = { go(NewRoutes.divisions(id)) },
        )
    }
    composable(
        NewRoutes.LEAGUE_FORM,
        arguments = listOf(
            navArgument("leagueId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { e ->
        LeagueFormScreen(
            leagueId = e.arguments?.getString("leagueId"),
            onBack = { nav.navigateUp() },
            onDone = { nav.navigateUp() },
        )
    }
    composable(NewRoutes.SEASONS, arguments = leagueIdArg) { e ->
        val id = e.arguments?.getString("leagueId").orEmpty()
        SeasonsScreen(leagueId = id, onBack = { nav.navigateUp() }, onNew = { go(NewRoutes.seasonForm(id)) })
    }
    composable(NewRoutes.SEASON_FORM, arguments = leagueIdArg) { e ->
        SeasonFormScreen(
            leagueId = e.arguments?.getString("leagueId").orEmpty(),
            onBack = { nav.navigateUp() },
            onDone = { nav.navigateUp() },
        )
    }
    composable(NewRoutes.DIVISIONS, arguments = leagueIdArg) { e ->
        DivisionsScreen(
            leagueId = e.arguments?.getString("leagueId").orEmpty(),
            onBack = { nav.navigateUp() },
            onViewStandings = { go(Destinations.STANDINGS) },
        )
    }
    composable(NewRoutes.TEAMS, arguments = leagueIdArg) { e ->
        val id = e.arguments?.getString("leagueId").orEmpty()
        LeagueTeamsScreen(leagueId = id, onBack = { nav.navigateUp() }, onGenerate = { go(NewRoutes.genFormat(id)) })
    }
    composable(NewRoutes.PROMOTION, arguments = leagueIdArg) { e ->
        PromotionScreen(
            leagueId = e.arguments?.getString("leagueId").orEmpty(),
            onBack = { nav.navigateUp() },
            onDone = { nav.navigateUp() },
        )
    }
    composable(NewRoutes.STANDINGS_RULES, arguments = leagueIdArg) { e ->
        StandingsRulesScreen(
            leagueId = e.arguments?.getString("leagueId").orEmpty(),
            onBack = { nav.navigateUp() },
            onDone = { nav.navigateUp() },
        )
    }
}
