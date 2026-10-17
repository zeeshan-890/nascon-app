package com.nascon.league.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Stadium
import androidx.compose.ui.graphics.vector.ImageVector
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import com.nascon.league.ui.navigation.Destinations

data class HomeCard(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val route: String,
)

fun homeCards(session: Session): List<HomeCard> {
    val clubId = session.user.clubId ?: Destinations.DEFAULT_CLUB_ID
    val fixtures = HomeCard("fixtures", "Fixtures", "See upcoming and past matches", Icons.Default.SportsSoccer, Destinations.FIXTURES)
    val store = HomeCard("store", "Store", "Browse official merchandise", Icons.Default.ShoppingBag, Destinations.STORE)
    return when (session.user.role) {
        Role.SUPER_ADMIN -> listOf(
            HomeCard("standings", "Leagues & Standings", "Tables for every league", Icons.Default.Leaderboard, Destinations.STANDINGS),
            fixtures,
            HomeCard("clubs", "Clubs", "Browse all clubs", Icons.Default.Groups, Destinations.club(clubId)),
            store,
            HomeCard("admin", "Admin overview", "Leagues, users and system status", Icons.Default.AdminPanelSettings, Destinations.ADMIN),
        )
        Role.LEAGUE_ORGANIZER -> listOf(
            HomeCard("score", "Score a live match", "Pick a match and record goals", Icons.Default.Stadium, Destinations.FIXTURES),
            HomeCard("standings", "My league standings", "Current table for your league", Icons.Default.Leaderboard, Destinations.STANDINGS),
            fixtures,
            store,
        )
        Role.CLUB_MANAGER -> listOf(
            HomeCard("roster", "Manage roster", "Add or edit your players", Icons.Default.Edit, Destinations.roster(clubId)),
            HomeCard("club", "My club profile", "Club details and squad", Icons.Default.Groups, Destinations.club(clubId)),
            fixtures,
            store,
        )
        Role.PLAYER -> listOf(
            HomeCard("club", "My club", "Your club and teammates", Icons.Default.Person, Destinations.club(clubId)),
            HomeCard("fixtures", "My fixtures", "Your upcoming matches", Icons.Default.SportsSoccer, Destinations.FIXTURES),
            HomeCard("standings", "Standings", "League tables", Icons.Default.Leaderboard, Destinations.STANDINGS),
            store,
        )
        Role.VIEWER -> listOf(
            fixtures,
            HomeCard("standings", "Standings", "League tables", Icons.Default.Leaderboard, Destinations.STANDINGS),
            HomeCard("clubs", "Clubs", "Browse all clubs", Icons.Default.Groups, Destinations.club(Destinations.DEFAULT_CLUB_ID)),
            store,
        )
    }
}
