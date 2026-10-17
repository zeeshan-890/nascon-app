package com.nascon.league.domain.model

enum class Role(val apiValue: String, val label: String) {
    SUPER_ADMIN("super_admin", "Super Admin"),
    LEAGUE_ORGANIZER("league_organizer", "League Organizer"),
    CLUB_MANAGER("club_manager", "Club Manager"),
    PLAYER("player", "Player"),
    VIEWER("viewer", "Viewer");

    companion object {
        fun fromApi(value: String?): Role = entries.firstOrNull { it.apiValue == value } ?: VIEWER
    }
}
