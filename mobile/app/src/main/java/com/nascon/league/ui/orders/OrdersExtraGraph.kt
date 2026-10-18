package com.nascon.league.ui.orders

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nascon.league.ui.navigation.Destinations
import com.nascon.league.ui.navigation.NewRoutes

fun NavGraphBuilder.ordersExtraGraph(nav: NavHostController) {
    composable(
        NewRoutes.CLUB_ORDERS,
        arguments = listOf(navArgument("clubId") { type = NavType.StringType }),
    ) { entry ->
        val clubId = entry.arguments?.getString("clubId").orEmpty()
        ClubOrdersScreen(
            clubId = clubId,
            onOpenOrder = { id -> nav.navigate(Destinations.orderDetail(id)) { launchSingleTop = true } },
            onBack = { nav.popBackStack() },
        )
    }
}

val ordersExtraTitles: Map<String, String> = mapOf(NewRoutes.CLUB_ORDERS to "Club orders")
