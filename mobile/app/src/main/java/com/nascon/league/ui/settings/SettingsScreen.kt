package com.nascon.league.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.remember
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.BuildConfig
import com.nascon.league.R
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ConfirmDialog
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Spacing

@Composable
fun SettingsScreen(
    onSignedOut: () -> Unit,
    onOpenSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val clearedMessage = stringResource(R.string.f2_saved_data_cleared)

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.cleared.collect { snackbar.showSnackbar(clearedMessage) } }
    LaunchedEffect(Unit) { vm.signedOut.collect { onSignedOut() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // Sync status row
            val pendingFlow = remember(container) { container.syncRepository.observePendingCount() }
            val pending by pendingFlow.collectAsStateWithLifecycle(initialValue = 0)
            val syncText = when {
                pending <= 0 -> stringResource(R.string.f11_sync_none)
                pending == 1 -> stringResource(R.string.f11_sync_pending_one)
                else -> stringResource(R.string.f11_sync_pending, pending)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .semantics(mergeDescendants = true) {}
                        .clickable(role = Role.Button, onClick = onOpenSync)
                        .padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.f11_sync_status_title), style = MaterialTheme.typography.titleMedium)
                        Text(syncText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Connection card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.f11_connection_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Icon(
                            if (state.useDemo) Icons.Default.CloudOff else Icons.Default.Cloud,
                            contentDescription = null,
                        )
                        Text(
                            stringResource(
                                R.string.f11_mode_label,
                                stringResource(if (state.useDemo) R.string.f11_mode_demo else R.string.f11_mode_live),
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Text(
                        if (state.address.isBlank()) stringResource(R.string.f11_connection_server_none)
                        else stringResource(R.string.f11_connection_server, state.address),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.f11_device_id, state.deviceId.ifEmpty { "…" }),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Text("Server", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            OutlinedTextField(
                value = state.address,
                onValueChange = vm::onAddressChange,
                label = { Text("Server address") },
                singleLine = true,
                isError = state.addressError != null,
                supportingText = state.addressError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.save() }),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(text = "Save address", onClick = vm::save, modifier = Modifier.fillMaxWidth())
            SecondaryButton(
                text = "Test connection",
                onClick = vm::testConnection,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.test != TestState.Loading,
            )
            when (val t = state.test) {
                TestState.Idle -> Unit
                TestState.Loading -> Text("Testing connection…", style = MaterialTheme.typography.bodyLarge)
                TestState.Success -> StatusChip(text = "Connected ✓", icon = Icons.Default.CheckCircle)
                is TestState.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = "Error", tint = MaterialTheme.colorScheme.error)
                    Text(t.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(
                        value = state.useDemo,
                        role = Role.Switch,
                        onValueChange = vm::setUseDemo,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Use demo data", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Shows sample data when the server isn't available",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(checked = state.useDemo, onCheckedChange = null)
            }

            Text("About", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Column {
                Text("Device ID", style = MaterialTheme.typography.bodyMedium)
                Text(
                    state.deviceId.ifEmpty { "…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column {
                Text("App version", style = MaterialTheme.typography.bodyMedium)
                Text(
                    BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SecondaryButton(
                text = stringResource(R.string.f2_clear_saved_data),
                onClick = { confirmClear = true },
                modifier = Modifier.fillMaxWidth(),
                destructive = false,
            )

            if (session != null) {
                SecondaryButton(
                    text = "Sign out",
                    onClick = { confirmSignOut = true },
                    modifier = Modifier.fillMaxWidth(),
                    destructive = true,
                )
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.f2_clear_saved_title),
            message = stringResource(R.string.f2_clear_saved_message),
            confirmLabel = stringResource(R.string.f2_clear_confirm),
            onConfirm = {
                confirmClear = false
                vm.clearSavedData()
            },
            onDismiss = { confirmClear = false },
        )
    }

    if (confirmSignOut) {
        val signOutPendingFlow = remember(container) { container.syncRepository.observePendingCount() }
        val signOutPending by signOutPendingFlow.collectAsStateWithLifecycle(initialValue = 0)
        ConfirmDialog(
            title = "Sign out?",
            message = when {
                signOutPending <= 0 -> stringResource(R.string.f10a_signout_message)
                signOutPending == 1 -> stringResource(R.string.f10a_unsynced_one)
                else -> stringResource(R.string.f10a_unsynced_many, signOutPending)
            },
            confirmLabel = "Sign out",
            onConfirm = {
                confirmSignOut = false
                vm.signOut()
            },
            onDismiss = { confirmSignOut = false },
            destructive = true,
        )
    }
}
