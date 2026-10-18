package com.nascon.league.ui.clubadmin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nascon.league.data.clubadmin.canManageClub
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.navigation.NewRoutes

/** Header titles for the routes registered by [clubAdminGraph]. */
val clubAdminTitles: Map<String, String> = mapOf(
    NewRoutes.CLUB_EDIT to "Edit club",
    NewRoutes.CLUB_STAFF to "Club staff",
    NewRoutes.CLUB_PRODUCTS to "Club products",
    NewRoutes.PRODUCT_EDIT to "Edit product",
    NewRoutes.PRODUCT_STOCK to "Update stock",
)

private val clubArg = navArgument("clubId") { type = NavType.StringType }
private val productArg = navArgument("productId") { type = NavType.StringType }

/** Shows [content] only for a super admin or the manager of [clubId]; everyone else gets a polite dead end. */
@Composable
private fun ManagerOnly(clubId: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val session by LocalAppContainer.current.sessionRepository.session.collectAsStateWithLifecycle()
    if (canManageClub(session, clubId)) content() else NoAccessState(Modifier, onBack)
}

private fun NavBackStackEntry.arg(name: String): String = arguments?.getString(name).orEmpty()

fun NavGraphBuilder.clubAdminGraph(nav: NavHostController) {
    val back: () -> Unit = { nav.popBackStack() }

    composable(NewRoutes.CLUB_EDIT, arguments = listOf(clubArg)) { e ->
        val clubId = e.arg("clubId")
        ManagerOnly(clubId, back) { EditClubScreen(clubId, back, Modifier) }
    }
    composable(NewRoutes.CLUB_STAFF, arguments = listOf(clubArg)) { e ->
        val clubId = e.arg("clubId")
        ManagerOnly(clubId, back) { EditStaffScreen(clubId, back, Modifier) }
    }
    composable(NewRoutes.CLUB_PRODUCTS, arguments = listOf(clubArg)) { e ->
        val clubId = e.arg("clubId")
        ManagerOnly(clubId, back) {
            ClubProductsScreen(
                clubId = clubId,
                onNewProduct = { nav.navigate(NewRoutes.productEdit(clubId, NEW_PRODUCT)) { launchSingleTop = true } },
                onEdit = { pid -> nav.navigate(NewRoutes.productEdit(clubId, pid)) { launchSingleTop = true } },
                onStock = { pid -> nav.navigate(NewRoutes.productStock(clubId, pid)) { launchSingleTop = true } },
                onBack = back,
                modifier = Modifier,
            )
        }
    }
    composable(NewRoutes.PRODUCT_EDIT, arguments = listOf(clubArg, productArg)) { e ->
        val clubId = e.arg("clubId")
        ManagerOnly(clubId, back) { ProductEditScreen(clubId, e.arg("productId"), back, Modifier) }
    }
    composable(NewRoutes.PRODUCT_STOCK, arguments = listOf(clubArg, productArg)) { e ->
        val clubId = e.arg("clubId")
        ManagerOnly(clubId, back) { StockScreen(clubId, e.arg("productId"), back, Modifier) }
    }
}
