package com.nascon.league.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.sync.SyncEvent
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Content only; the app shell supplies the top bar and bottom navigation. */
@Composable
fun SyncStatusScreen(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val vm: SyncStatusViewModel = viewModel(factory = SyncStatusViewModel.factory(container))
    val s by vm.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "waiting_title") { SectionTitle(stringResource(R.string.f9_waiting_title)) }

        if (s.needsLogin) {
            item(key = "needs_login") {
                IconText(Icons.Default.Warning, stringResource(R.string.f9_icon_not_applied), stringResource(R.string.f9_needs_login))
            }
        }

        if (s.pending.isEmpty()) {
            item(key = "waiting_empty") {
                Text(stringResource(R.string.f9_waiting_empty), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            items(s.pending, key = { "p_" + it.actionId }) { p ->
                ArenaCard(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {}) {
                    Text(p.label, style = MaterialTheme.typography.titleMedium, color = Arena.colors.ink)
                    val whenText = formatQueuedAt(p.queuedAtIso)
                    if (whenText != null) {
                        Text(
                            stringResource(R.string.f9_queued_at, whenText),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Arena.colors.inkSoft,
                        )
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    if (p.sending) {
                        StatusChip(stringResource(R.string.f9_chip_sending), Icons.Default.Sync, tone = ChipTone.Brand)
                    } else {
                        StatusChip(stringResource(R.string.f9_chip_pending), Icons.Default.Schedule, tone = ChipTone.Warning)
                    }
                }
            }
            item(key = "sync_now") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (s.syncing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    PrimaryButton(
                        text = if (s.syncing) stringResource(R.string.f9_syncing_progress) else stringResource(R.string.f9_sync_now),
                        onClick = vm::syncNow,
                        enabled = s.online && !s.syncing,
                        loading = s.syncing,
                        icon = if (s.syncing) null else Icons.Default.Sync,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    )
                    if (!s.online) {
                        Text(
                            stringResource(R.string.f9_offline_explain),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Arena.colors.inkSoft,
                        )
                    }
                }
            }
        }

        item(key = "results_title") {
            SectionTitle(stringResource(R.string.f9_results_title), Modifier.padding(top = Spacing.md))
        }
        val counts = s.counts
        if (counts != null) {
            item(key = "summary") {
                Text(
                    stringResource(R.string.f9_summary, counts.saved, counts.notApplied, counts.alreadySaved),
                    style = MaterialTheme.typography.titleMedium,
                    color = Arena.colors.ink,
                )
            }
        }
        if (s.results.isEmpty()) {
            item(key = "results_empty") {
                Text(stringResource(R.string.f9_results_empty), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            items(s.results, key = { "r_" + it.actionId }) { r ->
                ArenaCard(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {}) {
                    Text(r.label, style = MaterialTheme.typography.titleMedium, color = Arena.colors.ink)
                    Spacer(Modifier.height(Spacing.xs))
                    when (r.kind) {
                        ResultKind.APPLIED -> StatusChip(
                            stringResource(R.string.f9_result_applied),
                            Icons.Default.CheckCircle,
                            tone = ChipTone.Success,
                        )
                        ResultKind.DUPLICATE -> StatusChip(
                            stringResource(R.string.f9_result_duplicate),
                            Icons.Default.Info,
                            tone = ChipTone.Neutral,
                        )
                        ResultKind.REJECTED -> {
                            StatusChip(
                                stringResource(R.string.f9_icon_not_applied),
                                Icons.Default.Warning,
                                tone = ChipTone.Error,
                            )
                            if (!r.reason.isNullOrBlank()) {
                                Text(r.reason.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = Arena.colors.liveFg)
                            }
                            Text(
                                stringResource(R.string.notif_rejected_outcome),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Arena.colors.inkSoft,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.f9_result_synced_at, formatSyncedAt(r.syncedAt)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Arena.colors.inkSoft,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Arena.colors.ink, modifier = modifier.semantics { heading() })
}

@Composable
private fun IconText(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Call once from the app shell. Shows a snackbar for sync events:
 * AllSynced -> "All changes synced", Failed -> its message, NeedsLogin -> sign-in prompt.
 */
@Composable
fun SyncSnackbarEffect() {
    val container = LocalAppContainer.current
    val host = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val allSynced = stringResource(R.string.f9_snack_all_synced)
    val needsLogin = stringResource(R.string.f9_needs_login)
    LaunchedEffect(container, host, allSynced, needsLogin) {
        container.syncRepository.events.collect { event ->
            val message = when (event) {
                SyncEvent.AllSynced -> allSynced
                SyncEvent.NeedsLogin -> needsLogin
                is SyncEvent.Failed -> event.message
                is SyncEvent.Finished -> null
            }
            if (message != null) scope.launch { host.showSnackbar(message) }
        }
    }
}
