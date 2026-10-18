package com.nascon.league.ui.useradmin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.useradmin.AdminUserDto
import com.nascon.league.data.useradmin.displayName
import com.nascon.league.data.useradmin.roleEnum
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.domain.model.Role
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ArenaTextField
import com.nascon.league.ui.components.FeatureUnavailableState
import com.nascon.league.ui.components.FilterPill
import com.nascon.league.ui.components.GenericErrorState
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat

/** G02. */
@Composable
fun UsersListScreen(onOpen: (String) -> Unit, onNew: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val vm: UsersListViewModel = viewModel(factory = UsersListViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val load = state.load
    if (load is UaLoad.Failed && load.unavailable) {
        FeatureUnavailableState(modifier = modifier, onBack = onBack)
        return
    }
    val colors = Arena.colors
    val filters: List<Pair<String?, Int>> = listOf(
        null to R.string.ua_filter_all,
        Role.SUPER_ADMIN.apiValue to R.string.ua_filter_admin,
        Role.CLUB_MANAGER.apiValue to R.string.ua_filter_manager,
        Role.LEAGUE_ORGANIZER.apiValue to R.string.ua_filter_organizer,
        Role.PLAYER.apiValue to R.string.ua_filter_player,
        Role.VIEWER.apiValue to R.string.ua_filter_viewer,
    )
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "search") {
                ArenaTextField(
                    value = state.query,
                    onValueChange = vm::onQuery,
                    label = stringResource(R.string.ua_search_label),
                    placeholder = stringResource(R.string.ua_search_hint),
                    leadingIcon = Icons.Default.Search,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "filters") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    filters.forEach { (value, label) ->
                        FilterPill(stringResource(label), selected = state.role == value, onClick = { vm.onRole(value) })
                    }
                }
            }
            when (load) {
                UaLoad.Loading -> item(key = "loading") {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 3.dp, color = colors.brand)
                        Text(stringResource(R.string.ua_loading), color = colors.inkSoft, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                is UaLoad.Failed -> item(key = "error") {
                    GenericErrorStateInline(load.message, vm::retry)
                }
                is UaLoad.Data -> {
                    if (load.value.isEmpty()) {
                        item(key = "empty") {
                            ArenaCard(Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.PersonSearch, contentDescription = null, tint = colors.brand)
                                Text(
                                    stringResource(R.string.ua_users_empty_title), color = colors.ink, fontFamily = Montserrat,
                                    fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp),
                                )
                                Text(
                                    stringResource(R.string.ua_users_empty_message), color = colors.inkSoft, fontFamily = Montserrat,
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    } else {
                        items(load.value, key = { it.userId }) { u -> UserRow(u, onClick = { onOpen(u.userId) }) }
                    }
                }
            }
        }
        NewUserButton(onNew, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 24.dp))
    }
}

/** The shared GenericErrorState fills the screen; inside a list we show it as a card instead. */
@Composable
private fun GenericErrorStateInline(message: String, onRetry: () -> Unit) {
    val colors = Arena.colors
    ArenaCard(Modifier.fillMaxWidth()) {
        Text(message, color = colors.liveFg, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Box(
            Modifier
                .padding(top = 12.dp)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(50))
                .clickable(role = SemanticsRole.Button, onClick = onRetry)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(stringResource(R.string.st_try_again), color = colors.brand, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun UserRow(u: AdminUserDto, onClick: () -> Unit) {
    val colors = Arena.colors
    ArenaCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(u.displayName(), color = colors.ink, fontFamily = Montserrat, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(u.email, color = colors.inkSoft, fontFamily = Montserrat, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            UaRoleChip(u.roleEnum())
        }
        if (!u.active) UaStatusChip(active = false, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun NewUserButton(onClick: () -> Unit, modifier: Modifier) {
    val colors = Arena.colors
    val shape = RoundedCornerShape(28.dp)
    val desc = stringResource(R.string.ua_new_user_desc)
    Row(
        modifier = modifier
            .shadow(6.dp, shape)
            .clip(shape)
            .background(colors.buttonBrush)
            .clickable(onClickLabel = desc, role = SemanticsRole.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White)
        Text(stringResource(R.string.ua_new_user), color = androidx.compose.ui.graphics.Color.White, fontFamily = Montserrat, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
