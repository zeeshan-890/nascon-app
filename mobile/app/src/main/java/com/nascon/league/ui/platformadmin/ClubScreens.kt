package com.nascon.league.ui.platformadmin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ArenaTextField
import com.nascon.league.ui.components.EmptyStateCard
import com.nascon.league.ui.components.GenericErrorState
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.LastUpdatedLabel
import com.nascon.league.ui.components.LoadingState
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.RefreshableList
import com.nascon.league.ui.failure.userMessage
import com.nascon.league.ui.theme.Arena

/** G07: every club on the server (real `GET /api/v1/clubs`) with a New club button. */
@Composable
fun AdminClubsScreen(onClub: (String) -> Unit, onNew: () -> Unit, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: AdminClubsViewModel = viewModel(factory = AdminClubsViewModel.factory(container))
    val s by vm.state.collectAsStateWithLifecycle()
    val err = s.error

    Box(Modifier.fillMaxSize()) {
        when {
            s.clubs.isEmpty() && s.loading -> LoadingState()
            s.clubs.isEmpty() && err != null -> GenericErrorState(userMessage(err), onRetry = vm::refresh)
            s.clubs.isEmpty() -> EmptyStateCard(
                icon = Icons.Default.Groups,
                title = stringResource(R.string.pa_clubs_empty_title),
                message = stringResource(R.string.pa_clubs_empty_message),
                actionLabel = stringResource(R.string.pa_new_club),
                onAction = onNew,
            )
            else -> RefreshableList(refreshing = s.loading, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 104.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (err != null) {
                        item { InlineAlert(userMessage(err), AlertTone.WARNING) }
                    }
                    item { LastUpdatedLabel(s.updatedAt) }
                    items(s.clubs, key = { it.clubId }) { club ->
                        ArenaCard(Modifier.fillMaxWidth(), onClick = { onClub(club.clubId) }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(
                                    Modifier.size(44.dp).clip(CircleShape).background(Arena.colors.chipBg),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        club.name.trim().take(1).uppercase(),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Arena.colors.brand,
                                    )
                                }
                                Text(club.name, style = MaterialTheme.typography.titleMedium, color = Arena.colors.ink, modifier = Modifier.weight(1f))
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Arena.colors.brand)
                            }
                        }
                    }
                }
            }
        }
        if (s.clubs.isNotEmpty()) {
            PrimaryButton(
                text = stringResource(R.string.pa_new_club),
                onClick = onNew,
                icon = Icons.Default.Add,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 28.dp),
            )
        }
    }
}

/** G08: create a club (contract addition `POST /api/v1/admin/clubs`). */
@Composable
fun NewClubScreen(defaultTenant: String, onDone: () -> Unit, onCancel: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: NewClubViewModel = viewModel(factory = NewClubViewModel.factory(container, defaultTenant))
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.created.collect { onDone() } }

    val failure = s.failure
    if (failure?.unavailable == true) {
        PaFailure(failure, onRetry = null, onBack = onCancel)
        return
    }
    PaFormPage(
        bottomBar = {
            PaButtonPair(
                secondaryText = stringResource(R.string.pa_cancel),
                onSecondary = onCancel,
                primaryText = stringResource(R.string.pa_create_club),
                onPrimary = vm::submit,
                primaryLoading = s.submitting,
                secondaryEnabled = !s.submitting,
            )
        },
    ) {
        if (failure != null) InlineAlert(failure.message, AlertTone.ERROR)
        ArenaTextField(
            value = s.name, onValueChange = vm::onName, label = stringResource(R.string.pa_club_name),
            errorText = fieldError(s.nameProblem, duplicate = R.string.pa_err_club_duplicate),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            enabled = !s.submitting,
        )
        ArenaTextField(
            value = s.tenant, onValueChange = vm::onTenant, label = stringResource(R.string.pa_club_tenant),
            supportingText = stringResource(R.string.pa_club_tenant_hint),
            errorText = fieldError(s.tenantProblem, badFormat = R.string.pa_err_tenant_format),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            enabled = !s.submitting,
        )
        ArenaTextField(
            value = s.venue, onValueChange = vm::onVenue, label = stringResource(R.string.pa_club_venue),
            errorText = fieldError(s.venueProblem),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            enabled = !s.submitting,
        )
        ArenaTextField(
            value = s.city, onValueChange = vm::onCity, label = stringResource(R.string.pa_club_city),
            supportingText = stringResource(R.string.pa_optional),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            enabled = !s.submitting,
        )
    }
}
