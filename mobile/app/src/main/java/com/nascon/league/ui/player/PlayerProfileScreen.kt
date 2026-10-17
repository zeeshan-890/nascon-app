package com.nascon.league.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nascon.league.R
import com.nascon.league.data.api.dto.RosterPlayerDto
import com.nascon.league.ui.club.LoadingBox
import com.nascon.league.ui.club.PlayerRoleKind
import com.nascon.league.ui.club.playerRoleLabel
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.RefreshableList
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.navigation.HeaderExtra
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing

@Composable
fun PlayerProfileScreen(
    playerId: String,
    clubId: String?,
    onOpenHistory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val load = rememberClubLoad(clubId)
    val club = load.club
    val player = remember(club, playerId) { club?.roster?.firstOrNull { it.playerId == playerId } }
    HeaderSubtitle(player?.name ?: stringResource(R.string.np_player_title))
    when {
        clubId == null -> EmptyState(
            icon = Icons.Default.Person,
            title = stringResource(R.string.np_player_no_club_title),
            message = stringResource(R.string.np_player_no_club_message),
            actionLabel = stringResource(R.string.np_view_history),
            onAction = { onOpenHistory(playerId) },
            modifier = modifier,
        )
        club == null -> {
            val err = load.error
            Column(modifier.fillMaxSize()) {
                if (!load.checked || load.refreshing || err == null) LoadingBox() else LoadFailure(err, load.retry)
            }
        }
        player == null -> RefreshableList(
            refreshing = load.refreshing,
            onRefresh = load.retry,
            modifier = modifier.fillMaxSize(),
        ) {
            EmptyState(
                icon = Icons.Default.Person,
                title = stringResource(R.string.np_player_gone_title),
                message = stringResource(R.string.np_player_gone_message),
            )
        }
        else -> RefreshableList(
            refreshing = load.refreshing,
            onRefresh = load.retry,
            modifier = modifier.fillMaxSize(),
        ) {
            val squad = squadNames(club)[player.squadId]
            PlayerDetails(player, club.name, squad, onOpenHistory)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerDetails(
    p: RosterPlayerDto,
    clubName: String,
    squadName: String?,
    onOpenHistory: (String) -> Unit,
) {
    val initials = initialsOf(p.name)
    val avatarCd = stringResource(R.string.np_avatar_cd, p.name, initials)
    HeaderExtra {
        Column(
            Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            InitialsAvatar(initials, 72.dp, onGradient = true)
            Text(
                p.name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = Spacing.sm)
                    .semantics { heading(); contentDescription = avatarCd },
            )
        }
    }
    val role = playerRoleLabel(p.role)
    val roleText: String? = when (role.kind) {
        PlayerRoleKind.CAPTAIN -> stringResource(R.string.f5_role_captain)
        PlayerRoleKind.VICE_CAPTAIN -> stringResource(R.string.f5_role_vice_captain)
        PlayerRoleKind.STARTER -> stringResource(R.string.f5_role_starter)
        PlayerRoleKind.SUBSTITUTE -> stringResource(R.string.f5_role_substitute)
        PlayerRoleKind.OTHER -> role.text
        PlayerRoleKind.NONE -> null
    }
    val roleIcon: ImageVector = when (role.kind) {
        PlayerRoleKind.CAPTAIN -> Icons.Default.Star
        PlayerRoleKind.VICE_CAPTAIN -> Icons.Default.StarBorder
        PlayerRoleKind.STARTER -> Icons.Default.Check
        PlayerRoleKind.SUBSTITUTE -> Icons.Default.SwapVert
        else -> Icons.Default.Person
    }
    val historyCd = stringResource(R.string.np_view_history_cd, p.name)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (roleText != null || p.onLoan) {
            item(key = "chips") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    if (roleText != null) {
                        StatusChip(
                            roleText,
                            roleIcon,
                            tone = if (role.kind == PlayerRoleKind.CAPTAIN || role.kind == PlayerRoleKind.VICE_CAPTAIN) ChipTone.Brand else ChipTone.Neutral,
                        )
                    }
                    if (p.onLoan) StatusChip(stringResource(R.string.np_on_loan), Icons.Default.SyncAlt, tone = ChipTone.Warning)
                }
            }
        }
        item(key = "club") { InfoRow(stringResource(R.string.np_label_club), clubName) }
        if (squadName != null) item(key = "squad") { InfoRow(stringResource(R.string.np_label_squad), squadName) }
        item(key = "position") {
            InfoRow(
                stringResource(R.string.np_label_position),
                p.position.takeIf { it.isNotBlank() } ?: stringResource(R.string.np_position_unknown),
            )
        }
        item(key = "history") {
            SecondaryButton(
                text = stringResource(R.string.np_view_history),
                onClick = { onOpenHistory(p.playerId) },
                icon = Icons.Default.History,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm)
                    .semantics { contentDescription = historyCd },
            )
        }
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    ArenaCard(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = Arena.colors.inkSoft,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = Arena.colors.ink,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
