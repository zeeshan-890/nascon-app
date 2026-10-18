package com.nascon.league.ui.useradmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.useradmin.assignableRoles
import com.nascon.league.data.useradmin.displayName
import com.nascon.league.data.useradmin.roleRequiresClub
import com.nascon.league.data.useradmin.roleRequiresLeague
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.domain.model.Role
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.FeatureUnavailableState
import com.nascon.league.ui.components.GenericErrorState
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.LoadingState
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat

/** G05. */
@Composable
fun ChangeRoleScreen(userId: String, onDone: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val vm: UserRoleViewModel = viewModel(key = "ua-role-$userId", factory = UserRoleViewModel.factory(container, userId))
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val ctx = LocalContext.current

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is UserRoleEvent.Saved -> {
                    onDone()
                    snackbar.flash(ctx.getString(R.string.ua_role_saved, e.role.label))
                }
            }
        }
    }

    when (val load = s.load) {
        UaLoad.Loading -> LoadingState(stringResource(R.string.ua_loading), modifier)
        is UaLoad.Failed -> when {
            load.unavailable -> FeatureUnavailableState(modifier, onBack)
            load.notFound -> EmptyState(
                Icons.Default.PersonOff, stringResource(R.string.ua_not_found_title),
                stringResource(R.string.ua_not_found_message), modifier,
                actionLabel = stringResource(R.string.ua_back), onAction = onBack,
            )
            else -> GenericErrorState(load.message, vm::load, modifier)
        }
        is UaLoad.Data -> {
            val user = load.value
            val current = Role.fromApi(user.role)
            val selected = Role.fromApi(s.role)
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    user.displayName(),
                    modifier = Modifier.semantics { heading() },
                    color = Arena.colors.ink, fontFamily = Montserrat, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                )
                assignableRoles.forEach { r ->
                    UaRoleOption(r, selected = r == selected, current = r == current, onClick = { vm.selectRole(r.apiValue) }, enabled = !s.selfBlocked)
                }
                if (roleRequiresClub(selected) || selected == Role.PLAYER) {
                    UaPickerField(
                        label = stringResource(R.string.ua_field_club),
                        selectedText = s.options.clubs.firstOrNull { it.id == s.clubId }?.name,
                        options = s.options.clubs.map { it.id to it.name },
                        onSelect = vm::selectClub,
                        errorText = s.error?.takeIf { roleRequiresClub(selected) }?.let { uaFormErrorText(it) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                if (roleRequiresLeague(selected)) {
                    UaPickerField(
                        label = stringResource(R.string.ua_field_league),
                        selectedText = s.options.leagues.firstOrNull { it.id == s.leagueId }?.name,
                        options = s.options.leagues.map { it.id to it.name },
                        onSelect = vm::selectLeague,
                        errorText = s.error?.let { uaFormErrorText(it) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                if (s.selfBlocked) InlineAlert(stringResource(R.string.ua_role_self_blocked), AlertTone.INFO)
                s.message?.let { InlineAlert(it, AlertTone.ERROR) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(stringResource(R.string.ua_cancel), onClick = onBack, modifier = Modifier.weight(0.4f))
                    PrimaryButton(
                        stringResource(R.string.ua_role_save), onClick = vm::save,
                        modifier = Modifier.weight(0.6f), loading = s.saving, enabled = !s.selfBlocked,
                    )
                }
            }
        }
    }
}
