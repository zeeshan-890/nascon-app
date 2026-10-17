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
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.BuildConfig
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
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
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
            Text("Server", style = MaterialTheme.typography.titleMedium)
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

            Text("About", style = MaterialTheme.typography.titleMedium)
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

    if (confirmSignOut) {
        ConfirmDialog(
            title = "Sign out?",
            message = "Sign out? Pending offline actions stay on this device.",
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
