package com.nascon.league.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.BuildConfig
import com.nascon.league.R
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ArenaTextField
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.ConfirmDialog
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Arena
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
    val colors = Arena.colors

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.cleared.collect { snackbar.showSnackbar(clearedMessage) } }
    LaunchedEffect(Unit) { vm.signedOut.collect { onSignedOut() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
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
            val pendingFlow = remember(container) { container.syncRepository.observePendingCount() }
            val pending by pendingFlow.collectAsStateWithLifecycle(initialValue = 0)
            val syncText = when {
                pending <= 0 -> stringResource(R.string.f11_sync_none)
                pending == 1 -> stringResource(R.string.f11_sync_pending_one)
                else -> stringResource(R.string.f11_sync_pending, pending)
            }

            SectionHeading("Server")
            ArenaCard(modifier = Modifier.fillMaxWidth()) {
                ArenaTextField(
                    value = state.address,
                    onValueChange = vm::onAddressChange,
                    label = "Server address",
                    isError = state.addressError != null,
                    errorText = state.addressError,
                    supportingText = stringResource(R.string.ui_settings_server_hint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.save() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(
                    text = "Test connection",
                    onClick = vm::testConnection,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.test != TestState.Loading,
                )
                when (val t = state.test) {
                    TestState.Idle -> Unit
                    TestState.Loading -> Text("Testing connection…", style = MaterialTheme.typography.bodyLarge)
                    TestState.Success -> StatusChip(text = "Connected ✓", icon = Icons.Default.CheckCircle, tone = ChipTone.Success)
                    is TestState.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = "Error", tint = colors.liveFg)
                        Text(t.message, style = MaterialTheme.typography.bodyLarge, color = colors.liveFg)
                    }
                }
                PrimaryButton(text = "Save address", onClick = vm::save, modifier = Modifier.fillMaxWidth())
            }

            ArenaCard(modifier = Modifier.fillMaxWidth()) {
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
                        Text("Use demo data", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.ink)
                        Text(
                            stringResource(R.string.ui_settings_demo_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkSoft,
                        )
                    }
                    Switch(checked = state.useDemo, onCheckedChange = null)
                }
            }

            SectionHeading(stringResource(R.string.f11_connection_title))
            ArenaCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Icon(
                        if (state.useDemo) Icons.Default.CloudOff else Icons.Default.Cloud,
                        contentDescription = null,
                        tint = colors.brand,
                    )
                    Text(
                        stringResource(
                            R.string.f11_mode_label,
                            stringResource(if (state.useDemo) R.string.f11_mode_demo else R.string.f11_mode_live),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    if (state.address.isBlank()) stringResource(R.string.f11_connection_server_none)
                    else stringResource(R.string.f11_connection_server, state.address),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSoft,
                )
                Text(
                    stringResource(R.string.f11_device_id, state.deviceId.ifEmpty { "…" }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSoft,
                )
                Text(
                    "App version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSoft,
                )
            }

            ArenaCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenSync) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .semantics(mergeDescendants = true) { role = Role.Button },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, tint = colors.brand)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.f11_sync_status_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.ink)
                        Text(syncText, style = MaterialTheme.typography.bodyMedium, color = colors.inkSoft)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.inkSoft)
                }
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

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        fontSize = 15.sp,
        fontWeight = FontWeight.ExtraBold,
        color = Arena.colors.ink,
        modifier = Modifier.semantics { heading() },
    )
}
