package com.nascon.league.ui.fixturegen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nascon.league.data.fixturegen.GenFormat
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.navigation.NewRoutes

/** Header titles for every route registered by [fixtureGenGraph]. */
val fixtureGenTitles: Map<String, String> = mapOf(
    NewRoutes.GEN_FORMAT to "Generate fixtures",
    NewRoutes.GEN_ROUND_ROBIN to "Round robin",
    NewRoutes.GEN_SWISS to "Swiss",
    NewRoutes.GEN_SINGLE to "Single elimination",
    NewRoutes.GEN_DOUBLE to "Double elimination",
    NewRoutes.GEN_HYBRID to "Seed knockout",
    NewRoutes.GEN_PREVIEW to "Preview",
    NewRoutes.GEN_BRACKET_SINGLE to "Knockout",
    NewRoutes.GEN_BRACKET_DOUBLE to "Knockout",
    NewRoutes.GEN_SWISS_ROUNDS to "Swiss rounds",
)

internal fun formatSuffix(f: GenFormat): String = when (f) {
    GenFormat.ROUND_ROBIN -> "roundrobin"
    GenFormat.SWISS -> "swiss"
    GenFormat.SINGLE_ELIM -> "singleelim"
    GenFormat.DOUBLE_ELIM -> "doubleelim"
    GenFormat.HYBRID -> "hybrid"
}

internal fun navSuffix(n: GenNav): String = when (n) {
    GenNav.PREVIEW -> "preview"
    GenNav.BRACKET_SINGLE -> "bracket/single"
    GenNav.BRACKET_DOUBLE -> "bracket/double"
    GenNav.SWISS_ROUNDS -> "swiss/rounds"
}

/** One view model per flow: scoped to the format picker's back stack entry, so every step shares it. */
@Composable
private fun genViewModel(nav: NavHostController, entry: NavBackStackEntry, leagueId: String): FixtureGenViewModel {
    val container = LocalAppContainer.current
    val owner = remember(entry, leagueId) {
        try {
            nav.getBackStackEntry(NewRoutes.genFormat(leagueId))
        } catch (_: IllegalArgumentException) {
            entry
        }
    }
    return viewModel(
        viewModelStoreOwner = owner,
        key = "fixturegen-$leagueId",
        factory = FixtureGenViewModel.factory(container, leagueId),
    )
}

fun NavGraphBuilder.fixtureGenGraph(nav: NavHostController) {
    val args = listOf(navArgument("leagueId") { type = NavType.StringType })

    fun go(id: String, suffix: String) {
        nav.navigate(NewRoutes.gen(id, suffix)) { launchSingleTop = true }
    }

    fun finish() {
        nav.popBackStack(NewRoutes.GEN_FORMAT, inclusive = true)
    }

    composable(NewRoutes.GEN_FORMAT, arguments = args) { e ->
        val id = e.arguments?.getString("leagueId").orEmpty()
        GenFormatScreen(genViewModel(nav, e, id), id) { f -> go(id, formatSuffix(f)) }
    }
    composable(NewRoutes.GEN_ROUND_ROBIN, arguments = args) { e ->
        val id = e.arguments?.getString("leagueId").orEmpty()
        GenRoundRobinScreen(genViewModel(nav, e, id), id, { go(id, navSuffix(it)) }) { nav.popBackStack() }
    }
    composable(NewRoutes.GEN_SWISS, arguments = args) { e ->
        val id = e.arguments?.getString("leagueId").orEmpty()
        GenSwissScreen(genViewModel(nav, e, id), id, { go(id, navSuffix(it)) }) { nav.popBackStack() }
    }
    composable(NewRoutes.GEN_SINGLE, arguments = args) { e ->
        val id = e.arguments?.getString("leagueId").orEmpty()
        GenSingleScreen(genViewModel(nav, e, id), id, { go(id, navSuffix(it)) }) { nav.popBackStack() }
    }
    composable(NewRoutes.GEN_DOUBLE, arguments = args) { e ->
        val id = e.arguments?.getString("leagueId").orEmpty()
        GenDoubleScreen(genViewModel(nav, e, id), id, { go(id, navSuffix(it)) }) { nav.popBackStack() }
    }
    composable(NewRoutes.GEN_HYBRID, arguments = args) { e ->
        val id = e.arguments?.getString("leagueId").orEmpty()
        GenHybridScreen(genViewModel(nav, e, id), id, { go(id, navSuffix(it)) }) { nav.popBackStack() }
    }
    composable(NewRoutes.GEN_PREVIEW, arguments = args) { e ->
        val id = e.arguments?.getString("leagueId").orEmpty()
        GenPreviewScreen(genViewModel(nav, e, id), id, { nav.popBackStack() }, ::finish)
    }
    composable(NewRoutes.GEN_BRACKET_SINGLE, arguments = args) { e ->
        val id = e.arguments?.getString("leagueId").orEmpty()
        GenBracketSingleScreen(genViewModel(nav, e, id), id, { nav.popBackStack() }, ::finish)
    }
    composable(NewRoutes.GEN_BRACKET_DOUBLE, arguments = args) { e ->
        val id = e.arguments?.getString("leagueId").orEmpty()
        GenBracketDoubleScreen(genViewModel(nav, e, id), id, { nav.popBackStack() }, ::finish)
    }
    composable(NewRoutes.GEN_SWISS_ROUNDS, arguments = args) { e ->
        val id = e.arguments?.getString("leagueId").orEmpty()
        GenSwissRoundsScreen(genViewModel(nav, e, id), id, { nav.popBackStack() }, ::finish)
    }
}
