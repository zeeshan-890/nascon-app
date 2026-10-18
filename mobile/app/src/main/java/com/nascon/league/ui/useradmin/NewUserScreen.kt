package com.nascon.league.ui.useradmin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.useradmin.assignableRoles
import com.nascon.league.data.useradmin.roleRequiresClub
import com.nascon.league.data.useradmin.roleRequiresLeague
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.domain.model.Role
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.ArenaTextField
import com.nascon.league.ui.components.FeatureUnavailableState
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.navigation.LocalSnackbarHostState

/** G04. */
@Composable
fun NewUserScreen(onDone: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val vm: NewUserViewModel = viewModel(factory = NewUserViewModel.factory(container))
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val ctx = LocalContext.current

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is NewUserEvent.Created -> {
                    onDone()
                    snackbar.flash(ctx.getString(R.string.ua_created_done, e.name))
                }
            }
        }
    }
    if (s.blocked?.unavailable == true) {
        FeatureUnavailableState(modifier, onBack)
        return
    }
    val role = Role.fromApi(s.role)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ArenaTextField(
            value = s.name, onValueChange = vm::onName, label = stringResource(R.string.ua_field_name),
            errorText = s.errors["name"]?.let { uaFormErrorText(it) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        ArenaTextField(
            value = s.email, onValueChange = vm::onEmail, label = stringResource(R.string.ua_field_email),
            errorText = s.errors["email"]?.let { uaFormErrorText(it) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        ArenaTextField(
            value = s.password, onValueChange = vm::onPassword, label = stringResource(R.string.ua_field_password),
            errorText = s.errors["password"]?.let { uaFormErrorText(it) },
            supportingText = stringResource(R.string.ua_password_hint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (s.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                Box(
                    Modifier.size(48.dp).clickable(role = SemanticsRole.Button, onClick = vm::togglePassword),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (s.passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(if (s.passwordVisible) R.string.ua_password_hide else R.string.ua_password_show),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        UaPickerField(
            label = stringResource(R.string.ua_field_role),
            selectedText = role.label,
            options = assignableRoles.map { it.apiValue to it.label },
            onSelect = vm::onRole,
            errorText = s.errors["role"]?.let { uaFormErrorText(it) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (roleRequiresClub(role) || role == Role.PLAYER) {
            UaPickerField(
                label = stringResource(R.string.ua_field_club),
                selectedText = s.options.clubs.firstOrNull { it.id == s.clubId }?.name,
                options = s.options.clubs.map { it.id to it.name },
                onSelect = vm::onClub,
                errorText = s.errors["club"]?.let { uaFormErrorText(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (roleRequiresLeague(role)) {
            UaPickerField(
                label = stringResource(R.string.ua_field_league),
                selectedText = s.options.leagues.firstOrNull { it.id == s.leagueId }?.name,
                options = s.options.leagues.map { it.id to it.name },
                onSelect = vm::onLeague,
                errorText = s.errors["league"]?.let { uaFormErrorText(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        s.message?.let { InlineAlert(it, AlertTone.ERROR) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(stringResource(R.string.ua_cancel), onClick = onBack, modifier = Modifier.weight(0.4f))
            PrimaryButton(
                stringResource(R.string.ua_create), onClick = vm::submit,
                modifier = Modifier.weight(0.6f), loading = s.saving,
            )
        }
    }
}
