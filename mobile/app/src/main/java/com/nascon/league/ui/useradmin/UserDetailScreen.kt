package com.nascon.league.ui.useradmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.useradmin.AdminUserDto
import com.nascon.league.data.useradmin.SignInLabel
import com.nascon.league.data.useradmin.displayName
import com.nascon.league.data.useradmin.roleEnum
import com.nascon.league.data.useradmin.signInLabel
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.ConfirmDialog
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.FeatureUnavailableState
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.LoadingState
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.components.GenericErrorState
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.navigation.LocalSnackbarHostState

/** G03 + G06 (the confirm dialog). */
@Composable
fun UserDetailScreen(userId: String, onChangeRole: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val vm: UserDetailViewModel = viewModel(key = "ua-detail-$userId", factory = UserDetailViewModel.factory(container, userId))
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val ctx = LocalContext.current

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            val text = when (e) {
                is UserDetailEvent.Deactivated -> ctx.getString(R.string.ua_deactivated_done, e.name)
                is UserDetailEvent.Reactivated -> ctx.getString(R.string.ua_reactivated_done, e.name)
            }
            snackbar.showSnackbar(text, duration = SnackbarDuration.Short)
        }
    }

    when (val load = state.load) {
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
            HeaderSubtitle(user.displayName())
            DetailContent(user, vm.isSelf, state, vm, onChangeRole, modifier)
            if (state.confirmDeactivate) {
                ConfirmDialog(
                    title = stringResource(R.string.ua_deactivate_title),
                    message = stringResource(R.string.ua_deactivate_message, user.displayName()),
                    confirmLabel = stringResource(R.string.ua_deactivate_confirm),
                    onConfirm = vm::confirmDeactivate,
                    onDismiss = vm::dismissDialog,
                    destructive = true,
                    dismissLabel = stringResource(R.string.ua_cancel),
                )
            }
        }
    }
}

@Composable
private fun DetailContent(
    user: AdminUserDto,
    isSelf: Boolean,
    state: UserDetailUiState,
    vm: UserDetailViewModel,
    onChangeRole: () -> Unit,
    modifier: Modifier,
) {
    val none = stringResource(R.string.ua_none)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UaInfoRow(stringResource(R.string.ua_label_email)) { UaInfoText(user.email) }
        UaInfoRow(stringResource(R.string.ua_label_role)) { UaRoleChip(user.roleEnum()) }
        user.tenantName?.let { t -> UaInfoRow(stringResource(R.string.ua_label_tenant)) { UaInfoText(t) } }
        if (user.clubId != null) {
            UaInfoRow(stringResource(R.string.ua_label_club)) { UaInfoText(user.clubName ?: none) }
        }
        if (user.leagueIds.isNotEmpty()) {
            UaInfoRow(stringResource(R.string.ua_label_leagues)) {
                UaInfoText(user.leagueNames.ifEmpty { user.leagueIds }.joinToString(", "))
            }
        }
        UaInfoRow(stringResource(R.string.ua_label_status)) { UaStatusChip(user.active) }
        UaInfoRow(stringResource(R.string.ua_label_last_sign_in)) {
            UaInfoText(
                when (val s = signInLabel(user.lastSignInAt)) {
                    SignInLabel.Never -> stringResource(R.string.ua_never)
                    is SignInLabel.Today -> stringResource(R.string.ua_today_at, s.time)
                    is SignInLabel.On -> s.date
                },
            )
        }
        if (state.selfBlocked || isSelf) {
            InlineAlert(stringResource(R.string.ua_self_note), AlertTone.INFO)
        }
        state.actionError?.let { InlineAlert(it, AlertTone.ERROR) }
        PrimaryButton(
            text = stringResource(R.string.ua_change_role),
            onClick = onChangeRole,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Default.SwapHoriz,
        )
        if (user.active) {
            SecondaryButton(
                text = stringResource(R.string.ua_deactivate),
                onClick = vm::askDeactivate,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Default.Block,
                destructive = true,
                enabled = !state.working,
            )
        } else {
            SecondaryButton(
                text = stringResource(R.string.ua_reactivate),
                onClick = vm::reactivate,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Default.Restore,
                enabled = !state.working,
            )
        }
    }
}
