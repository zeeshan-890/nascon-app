package com.nascon.league.ui.roster

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.data.api.dto.ClubDto
import com.nascon.league.data.api.dto.RosterPlayerDto
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.club.LoadingBox
import com.nascon.league.ui.club.NotSavedState
import com.nascon.league.ui.club.showPendingChip
import com.nascon.league.ui.components.ConfirmDialog
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.LastUpdatedLabel
import com.nascon.league.ui.components.RefreshableList
import com.nascon.league.ui.components.SavedDataBadge
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.match.isConnectivity
import com.nascon.league.ui.match.isForbidden
import com.nascon.league.ui.match.isNotFound
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Spacing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun RosterScreen(clubId: String, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val vm: RosterViewModel = viewModel(
        key = "roster-$clubId",
        factory = RosterViewModel.factory(container, clubId),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    val msgTransfer = stringResource(R.string.f7_saved_transfer)
    val msgLoan = stringResource(R.string.f7_saved_loan)
    val msgRelease = stringResource(R.string.f7_saved_release)
    val msgOffline = stringResource(R.string.f7_saved_offline)
    val msgFailed = stringResource(R.string.f7_save_failed)
    LaunchedEffect(vm, msgTransfer, msgLoan, msgRelease, msgOffline, msgFailed) {
        vm.events.collect { e ->
            val text = when (e) {
                is RosterEvent.Saved -> when (e.kind) {
                    SavedKind.TRANSFER -> msgTransfer
                    SavedKind.LOAN -> msgLoan
                    SavedKind.RELEASE -> msgRelease
                    SavedKind.OFFLINE -> msgOffline
                }
                RosterEvent.Failed -> msgFailed
            }
            snackbar.showSnackbar(text)
        }
    }

    val userId = session?.user?.userId
    val pendingFor: (String) -> Flow<Boolean> = remember(userId) {
        { playerId ->
            container.pendingRepository.observeForEntity(playerId)
                .map { showPendingChip(it, userId) }
                .distinctUntilChanged()
        }
    }

    val data = s.data
    val club = data.club
    Box(modifier.fillMaxSize()) {
        if (club == null) {
            val err = data.refresh.error
            when {
                !data.cacheChecked || data.refresh.refreshing || err == null -> LoadingBox()
                err.isNotFound() -> EmptyState(
                    icon = Icons.Default.EventBusy,
                    title = stringResource(R.string.f5_gone_title),
                    message = stringResource(R.string.f5_gone_message),
                    actionLabel = stringResource(R.string.f5_back),
                    onAction = { back?.onBackPressed() },
                )
                err.isForbidden() -> EmptyState(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.f5_forbidden_title),
                    message = stringResource(R.string.f5_forbidden_message),
                    actionLabel = stringResource(R.string.f5_back),
                    onAction = { back?.onBackPressed() },
                )
                err.isConnectivity() -> NotSavedState(vm::refresh)
                else -> ErrorState(ErrorMapper.message(err), vm::refresh)
            }
        } else {
            val canManage = RosterLogic.canManage(session, club.clubId)
            RefreshableList(
                refreshing = data.refresh.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                RosterList(
                    club = club,
                    showSaved = data.offline || data.refresh.error != null,
                    refreshFailed = data.refresh.error != null && !data.offline,
                    updatedAt = data.updatedAt,
                    canManage = canManage,
                    pendingFor = pendingFor,
                    onPlayer = vm::openPlayer,
                )
            }
            if (canManage) RosterOverlays(club, s.flow, vm)
        }
    }
}

@Composable
private fun RosterOverlays(club: ClubDto, flow: RosterFlowState, vm: RosterViewModel) {
    val player = club.roster.firstOrNull { it.playerId == flow.playerId }
    if (flow.step == RosterStep.NONE) return
    if (player == null) {
        // the player vanished from the refreshed cache; close whatever was open
        LaunchedEffect(flow.playerId) { vm.dismiss() }
        return
    }
    if (flow.step == RosterStep.ACTIONS) {
        ActionSheet(player, onPick = vm::chooseType, onDismiss = vm::dismiss)
        return
    }
    val draft = flow.draft ?: return
    val form = draft.toForm()
    val errors = RosterLogic.validate(form, player.squadId)
    val canSubmit = RosterLogic.isValid(form, player.squadId)
    val fromName = club.squads.firstOrNull { it.squadId == player.squadId }?.name
    val destinations = remember(club.squads, club.roster, player.squadId, draft.type) {
        RosterLogic.destinationSquads(club.squads, player.squadId, draft.type)
            .map { SquadOption(it, RosterLogic.squadSize(club.roster, it.squadId)) }
    }
    MoveFormSheet(
        player = player,
        fromSquadName = fromName,
        draft = draft,
        destinations = destinations,
        errors = errors,
        canSubmit = canSubmit,
        submitting = flow.submitting,
        onEdit = vm::edit,
        onSubmit = vm::requestConfirm,
        onBack = vm::backToActions,
        onDismiss = vm::dismiss,
    )
    if (flow.step == RosterStep.CONFIRM && canSubmit) {
        val info = RosterLogic.confirmation(form, player, club.squads)
        val date = formatRosterDate(info.effectiveDate)
        val end = info.loanEndDate?.let { formatRosterDate(it) }
        val from = info.fromSquadName
        val to = info.toSquadName.orEmpty()
        val message = when (info.type) {
            MoveType.TRANSFER ->
                if (from != null) stringResource(R.string.f7_confirm_transfer, info.playerName, from, to, date)
                else stringResource(R.string.f7_confirm_transfer_nofrom, info.playerName, to, date)
            MoveType.LOAN ->
                if (from != null) stringResource(R.string.f7_confirm_loan, info.playerName, from, to, date, end.orEmpty())
                else stringResource(R.string.f7_confirm_loan_nofrom, info.playerName, to, date, end.orEmpty())
            MoveType.RELEASE ->
                if (from != null) stringResource(R.string.f7_confirm_release, info.playerName, from, date)
                else stringResource(R.string.f7_confirm_release_nofrom, info.playerName, date)
        }
        val title = when (info.type) {
            MoveType.TRANSFER -> stringResource(R.string.f7_confirm_title_transfer)
            MoveType.LOAN -> stringResource(R.string.f7_confirm_title_loan)
            MoveType.RELEASE -> stringResource(R.string.f7_confirm_title_release)
        }
        ConfirmDialog(
            title = title,
            message = message,
            confirmLabel = stringResource(R.string.f7_confirm_yes),
            dismissLabel = stringResource(R.string.f7_cancel),
            destructive = info.type == MoveType.RELEASE,
            onConfirm = vm::confirm,
            onDismiss = vm::cancelConfirm,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionSheet(
    player: RosterPlayerDto,
    onPick: (MoveType) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Spacing.md)) {
            Text(
                player.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.md).semantics { heading() },
            )
            if (RosterLogic.showsOnLoanWarning(player)) {
                Row(
                    Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.f7_on_loan_warning), style = MaterialTheme.typography.bodyLarge)
                }
            }
            ActionRow(Icons.Default.SwapHoriz, stringResource(R.string.f7_action_transfer)) { onPick(MoveType.TRANSFER) }
            ActionRow(Icons.Default.SyncAlt, stringResource(R.string.f7_action_loan)) { onPick(MoveType.LOAN) }
            ActionRow(Icons.Default.ExitToApp, stringResource(R.string.f7_action_release)) { onPick(MoveType.RELEASE) }
        }
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RosterList(
    club: ClubDto,
    showSaved: Boolean,
    refreshFailed: Boolean,
    updatedAt: java.time.Instant?,
    canManage: Boolean,
    pendingFor: (String) -> Flow<Boolean>,
    onPlayer: (String) -> Unit,
) {
    val groups = remember(club.roster, club.squads) { RosterLogic.groupBySquad(club.roster, club.squads) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (showSaved) SavedDataBadge()
                    LastUpdatedLabel(updatedAt)
                }
                if (refreshFailed) {
                    Text(
                        stringResource(R.string.f5_refresh_failed_saved),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
                Text(
                    stringResource(if (canManage) R.string.f7_hint_manage else R.string.f7_read_only),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }
        if (club.roster.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.f5_roster_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                )
            }
        } else {
            groups.forEach { group ->
                item(key = "sq-" + group.squad.squadId) {
                    Text(
                        stringResource(R.string.f7_squad_header, group.squad.name, group.players.size),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                            .semantics { heading() },
                    )
                }
                if (group.players.isEmpty()) {
                    item(key = "sq-empty-" + group.squad.squadId) {
                        Text(
                            stringResource(R.string.f7_squad_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                        )
                    }
                }
                items(group.players, key = { "pl-" + it.playerId }) { p ->
                    val pending by produceState(false, p.playerId) {
                        pendingFor(p.playerId).collect { value = it }
                    }
                    RosterPlayerRow(p, pending, canManage) { onPlayer(p.playerId) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RosterPlayerRow(p: RosterPlayerDto, pending: Boolean, canManage: Boolean, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p.position.isNotBlank()) {
                Text(p.position, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (p.onLoan || pending) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    if (p.onLoan) StatusChip(stringResource(R.string.f5_on_loan), Icons.Default.SyncAlt)
                    if (pending) StatusChip(stringResource(R.string.f5_pending), Icons.Default.Schedule)
                }
            }
        }
    }
    val cardModifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md)
    if (canManage) {
        OutlinedCard(onClick = onClick, modifier = cardModifier) { content() }
    } else {
        OutlinedCard(modifier = cardModifier.semantics(mergeDescendants = true) {}) { content() }
    }
}
