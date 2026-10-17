package com.nascon.league.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascon.league.R
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.navigation.HeaderSubtitle

/** D03: "My clubs" timeline for the signed-in player. */
@Composable
fun MyHistoryScreen(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    val playerId = session?.user?.playerId
    HeaderSubtitle(stringResource(R.string.np_my_clubs_title))
    if (playerId == null) {
        EmptyState(
            icon = Icons.Default.Person,
            title = stringResource(R.string.np_my_history_no_player_title),
            message = stringResource(R.string.np_my_history_no_player_message),
            modifier = modifier,
        )
    } else {
        HistoryTimeline(playerId, highlightCurrent = true, modifier = modifier)
    }
}
