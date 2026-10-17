package com.nascon.league.ui.club

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascon.league.R
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.EmptyState

/** The "Club" tab / Home card target: renders by role via [clubDestinationFor]. */
@Composable
fun ClubTabScreen(
    onOpenClub: (String) -> Unit,
    onManageRoster: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlayer: ((playerId: String, clubId: String) -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    val destination = remember(session?.user?.role, session?.user?.clubId) { clubDestinationFor(session) }
    when (destination) {
        is ClubDestination.Profile -> ClubProfileScreen(destination.clubId, onManageRoster, modifier, onOpenPlayer)
        ClubDestination.List -> ClubsListScreen(onOpenClub, modifier)
        ClubDestination.NoClub -> EmptyState(
            icon = Icons.Default.Groups,
            title = stringResource(R.string.f5_no_club_title),
            message = stringResource(R.string.f5_no_club_message),
            modifier = modifier,
        )
    }
}
