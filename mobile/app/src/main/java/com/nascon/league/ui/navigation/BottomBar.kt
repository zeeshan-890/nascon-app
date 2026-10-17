package com.nascon.league.ui.navigation

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class TabItem(val route: String, val label: String, val icon: ImageVector)

val tabItems = listOf(
    TabItem(Destinations.HOME, "Home", Icons.Default.Home),
    TabItem(Destinations.FIXTURES, "Fixtures", Icons.Default.SportsSoccer),
    TabItem(Destinations.STANDINGS, "Standings", Icons.Default.Leaderboard),
    TabItem(Destinations.STORE, "Store", Icons.Default.ShoppingBag),
    TabItem(Destinations.CLUBS, "Club", Icons.Default.Groups),
)

/** Route without query part, so "fixtures?filter={filter}" counts as the Fixtures tab. */
fun baseRoute(route: String?): String? = route?.substringBefore('?')

fun isTabRoute(route: String?): Boolean = baseRoute(route).let { r -> tabItems.any { it.route == r } }

@Composable
fun BottomBar(
    currentRoute: String?,
    onTabClick: (TabItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        tabItems.forEach { tab ->
            NavigationBarItem(
                selected = baseRoute(currentRoute) == tab.route,
                onClick = { onTabClick(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}
