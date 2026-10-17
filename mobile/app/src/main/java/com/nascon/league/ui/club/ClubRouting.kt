package com.nascon.league.ui.club

import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session

sealed interface ClubDestination {
    data class Profile(val clubId: String) : ClubDestination
    data object List : ClubDestination
    data object NoClub : ClubDestination
}

/**
 * Which screen the "Club" tab / Home card shows. Managers and players go to their own club,
 * everyone else browses the clubs list.
 */
fun clubDestinationFor(session: Session?): ClubDestination {
    val user = session?.user ?: return ClubDestination.List
    return when (user.role) {
        Role.CLUB_MANAGER, Role.PLAYER -> {
            val id = user.clubId?.trim().orEmpty()
            if (id.isEmpty()) ClubDestination.NoClub else ClubDestination.Profile(id)
        }
        Role.LEAGUE_ORGANIZER, Role.VIEWER, Role.SUPER_ADMIN -> ClubDestination.List
    }
}
