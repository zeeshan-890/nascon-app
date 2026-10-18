package com.nascon.league.ui.useradmin

import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nascon.league.ui.navigation.Destinations
import com.nascon.league.ui.navigation.NewRoutes

private fun NavHostController.go(route: String) = navigate(route) { launchSingleTop = true }

/**
 * Admin home (replaces the placeholder at Destinations.ADMIN) and the user management screens. Super admin only.
 * "admin/users/new" is registered before "admin/users/{userId}" so the literal route wins.
 */
fun NavGraphBuilder.userAdminGraph(nav: NavHostController) {
    composable(Destinations.ADMIN) {
        AdminHomeScreen(
            onUsers = { nav.go(NewRoutes.ADMIN_USERS) },
            onClubs = { nav.go(NewRoutes.ADMIN_CLUBS) },
            onSports = { nav.go(NewRoutes.ADMIN_SPORTS) },
            onLeagues = { nav.go(NewRoutes.LEAGUE_ADMIN_LIST) },
            onAudit = { nav.go(NewRoutes.ADMIN_AUDIT) },
            onAccess = { nav.go(NewRoutes.ADMIN_ACCESS) },
            onStatus = { nav.go(NewRoutes.ADMIN_STATUS) },
            modifier = Modifier,
        )
    }
    composable(NewRoutes.ADMIN_USERS) {
        UsersListScreen(
            onOpen = { nav.go(NewRoutes.adminUser(it)) },
            onNew = { nav.go(NewRoutes.ADMIN_USER_NEW) },
            onBack = { nav.popBackStack() },
        )
    }
    composable(NewRoutes.ADMIN_USER_NEW) {
        NewUserScreen(onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() })
    }
    composable(
        NewRoutes.ADMIN_USER_DETAIL,
        arguments = listOf(navArgument("userId") { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString("userId").orEmpty()
        UserDetailScreen(
            userId = id,
            onChangeRole = { nav.go(NewRoutes.adminUserRole(id)) },
            onBack = { nav.popBackStack() },
        )
    }
    composable(
        NewRoutes.ADMIN_USER_ROLE,
        arguments = listOf(navArgument("userId") { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString("userId").orEmpty()
        ChangeRoleScreen(userId = id, onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() })
    }
}

val userAdminTitles: Map<String, String> = mapOf(
    Destinations.ADMIN to "Admin",
    NewRoutes.ADMIN_USERS to "Users",
    NewRoutes.ADMIN_USER_NEW to "New user",
    NewRoutes.ADMIN_USER_DETAIL to "User",
    NewRoutes.ADMIN_USER_ROLE to "Change role",
)
