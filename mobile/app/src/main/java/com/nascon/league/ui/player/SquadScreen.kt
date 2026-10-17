package com.nascon.league.ui.player

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nascon.league.R
import com.nascon.league.data.api.dto.RosterPlayerDto
import com.nascon.league.ui.club.LoadingBox
import com.nascon.league.ui.club.PlayerRoleKind
import com.nascon.league.ui.club.groupRosterByPosition
import com.nascon.league.ui.club.playerRoleLabel
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.LastUpdatedLabel
import com.nascon.league.ui.components.RefreshableList
import com.nascon.league.ui.components.SavedDataBadge
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing

/** D04: tappable squad list for a club, from the cached club payload. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SquadScreen(
    clubId: String,
    onOpenPlayer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val load = rememberClubLoad(clubId)
    val club = load.club
    val players = remember(club) {
        club?.let { c -> groupRosterByPosition(c.roster).flatMap { it.players } }.orEmpty()
    }
    val res = LocalContext.current.resources
    HeaderSubtitle(
        club?.let { "${it.name} · " + res.getQuantityString(R.plurals.np_squad_count, players.size, players.size) }
            ?: stringResource(R.string.np_squad_title),
    )
    if (club == null) {
        val err = load.error
        Column(modifier.fillMaxSize()) {
            if (!load.checked || load.refreshing || err == null) LoadingBox() else LoadFailure(err, load.retry)
        }
        return
    }
    RefreshableList(refreshing = load.refreshing, onRefresh = load.retry, modifier = modifier.fillMaxSize()) {
        if (players.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Groups,
                title = stringResource(R.string.np_squad_empty_title),
                message = stringResource(R.string.np_squad_empty_message),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (load.error != null || load.updatedAt != null) {
                    item(key = "status") {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            if (load.error != null) SavedDataBadge()
                            LastUpdatedLabel(load.updatedAt)
                        }
                    }
                }
                items(players, key = { "sq-" + it.playerId }) { p -> SquadRow(p, onOpenPlayer) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SquadRow(p: RosterPlayerDto, onOpen: (String) -> Unit) {
    val role = playerRoleLabel(p.role)
    val roleText: String? = when (role.kind) {
        PlayerRoleKind.CAPTAIN -> stringResource(R.string.f5_role_captain)
        PlayerRoleKind.VICE_CAPTAIN -> stringResource(R.string.f5_role_vice_captain)
        PlayerRoleKind.STARTER -> stringResource(R.string.f5_role_starter)
        PlayerRoleKind.SUBSTITUTE -> stringResource(R.string.f5_role_substitute)
        PlayerRoleKind.OTHER -> role.text
        PlayerRoleKind.NONE -> null
    }
    val cd = stringResource(R.string.np_squad_open_cd, p.name)
    ArenaCard(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .semantics(mergeDescendants = true) { contentDescription = cd },
        onClick = { onOpen(p.playerId) },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            InitialsAvatar(initialsOf(p.name), 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    p.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Arena.colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (p.position.isNotBlank()) {
                    Text(p.position, style = MaterialTheme.typography.bodyMedium, color = Arena.colors.inkSoft)
                }
                if (roleText != null || p.onLoan) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        if (roleText != null) {
                            val captain = role.kind == PlayerRoleKind.CAPTAIN || role.kind == PlayerRoleKind.VICE_CAPTAIN
                            StatusChip(
                                roleText,
                                when (role.kind) {
                                    PlayerRoleKind.CAPTAIN -> Icons.Default.Star
                                    PlayerRoleKind.VICE_CAPTAIN -> Icons.Default.StarBorder
                                    else -> Icons.Default.Person
                                },
                                tone = if (captain) ChipTone.Brand else ChipTone.Neutral,
                            )
                        }
                        if (p.onLoan) StatusChip(stringResource(R.string.np_on_loan), Icons.Default.SyncAlt, tone = ChipTone.Warning)
                    }
                }
            }
        }
    }
}
