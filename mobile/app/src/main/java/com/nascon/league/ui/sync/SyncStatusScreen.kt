package com.nascon.league.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.navigation.LocalSnackbarHostState
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
                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(p.label, style = MaterialTheme.typography.bodyLarge)
                    val whenText = formatQueuedAt(p.queuedAtIso)
                    if (whenText != null) {
                        Text(
                            stringResource(R.string.f9_queued_at, whenText),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (p.sending) {
                        StatusChip(stringResource(R.string.f9_chip_sending), Icons.Default.Sync)
                    } else {
                        StatusChip(stringResource(R.string.f9_chip_pending), Icons.Default.Schedule)
                    }
                    HorizontalDivider(Modifier.padding(top = Spacing.sm))
                }
            }
            item(key = "sync_now") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (s.syncing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Button(
                        onClick = vm::syncNow,
                        enabled = s.online && !s.syncing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        if (s.syncing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.f9_syncing_progress), Modifier.padding(start = Spacing.sm))
                        } else {
                            Text(stringResource(R.string.f9_sync_now))
                        }
                    }
                    if (!s.online) {
                        Text(
                            stringResource(R.string.f9_offline_explain),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                )
            }
        }
        if (s.results.isEmpty()) {
            item(key = "results_empty") {
                Text(stringResource(R.string.f9_results_empty), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            items(s.results, key = { "r_" + it.actionId }) { r ->
                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(r.label, style = MaterialTheme.typography.bodyLarge)
                    when (r.kind) {
                        ResultKind.APPLIED -> IconText(
                            Icons.Default.CheckCircle,
                            stringResource(R.string.f9_icon_saved),
                            stringResource(R.string.f9_result_applied),
                        )
                        ResultKind.DUPLICATE -> IconText(
                            Icons.Default.Info,
                            stringResource(R.string.f9_icon_duplicate),
                            stringResource(R.string.f9_result_duplicate),
                        )
                        ResultKind.REJECTED -> IconText(
                            Icons.Default.Warning,
                            stringResource(R.string.f9_icon_not_applied),
                            r.reason.orEmpty(),
                        )
                    }
                    Text(
                        stringResource(R.string.f9_result_synced_at, formatSyncedAt(r.syncedAt)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.padding(top = Spacing.sm))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.semantics { heading() })
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
