package com.nascon.league.ui.platformadmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.LastUpdatedLabel
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.theme.Arena

@Composable
private fun StateChip(state: CheckState, okText: String) {
    when (state) {
        CheckState.OK -> StatusChip(okText, Icons.Default.CheckCircle, tone = ChipTone.Success)
        CheckState.SLOW -> StatusChip(stringResource(R.string.pa_status_slow), Icons.Default.Warning, tone = ChipTone.Warning)
        CheckState.STARTING -> StatusChip(stringResource(R.string.pa_status_starting), Icons.Default.Warning, tone = ChipTone.Warning)
        CheckState.DOWN -> StatusChip(stringResource(R.string.pa_status_down), Icons.Default.ErrorOutline, tone = ChipTone.Error)
    }
}

@Composable
private fun StatusRow(label: String, row: CheckRow?, checking: Boolean, okText: String) {
    ArenaCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = Arena.colors.ink, modifier = Modifier.weight(1f))
            when {
                row != null -> StateChip(row.state, okText)
                checking -> Text(stringResource(R.string.pa_status_checking), style = MaterialTheme.typography.bodyMedium, color = Arena.colors.inkSoft)
            }
        }
        row?.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Arena.colors.inkSoft, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** G16: real `GET /health` and `GET /api/v1/ping` in live mode; simulated in demo mode. */
@Composable
fun SystemStatusScreen() {
    val container = LocalAppContainer.current
    val vm: SystemStatusViewModel = viewModel(factory = SystemStatusViewModel.factory(container))
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            PaPage {
                if (s.demo) InlineAlert(stringResource(R.string.pa_status_demo), AlertTone.INFO)
                StatusRow(stringResource(R.string.pa_status_server), s.server, s.checking, stringResource(R.string.pa_status_online))
                StatusRow(stringResource(R.string.pa_status_database), s.database, s.checking, stringResource(R.string.pa_status_healthy))
                s.server?.millis?.let { PaInfoRow(stringResource(R.string.pa_status_response), stringResource(R.string.pa_status_ms, it)) }
                if (s.address.isNotBlank() && !s.demo) PaInfoRow(stringResource(R.string.pa_status_address), s.address)
                if (version.isNotBlank()) PaInfoRow(stringResource(R.string.pa_status_version), version)
                if (s.checkedAt != null) LastUpdatedLabel(s.checkedAt)
                PaNote(stringResource(R.string.pa_status_scope_note))
            }
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            PrimaryButton(
                text = stringResource(R.string.pa_check_again),
                onClick = vm::check,
                loading = s.checking,
                icon = Icons.Default.Refresh,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
