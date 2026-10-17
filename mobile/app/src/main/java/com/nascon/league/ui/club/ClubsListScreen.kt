package com.nascon.league.ui.club

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.nascon.league.ui.components.ArenaTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.core.config.ServerConfigStore
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.LastUpdatedLabel
import com.nascon.league.ui.components.RefreshableList
import com.nascon.league.ui.components.SavedDataBadge
import com.nascon.league.ui.match.isConnectivity
import com.nascon.league.ui.theme.Spacing

@Composable
fun ClubsListScreen(
    onOpenClub: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm: ClubsListViewModel = viewModel(factory = ClubsListViewModel.factory(container))
    val s by vm.state.collectAsStateWithLifecycle()
    val baseUrl by container.configStore.baseUrl.collectAsStateWithLifecycle(
        initialValue = ServerConfigStore.DEFAULT_BASE_URL,
    )
    val allClubs = s.clubs
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val clubs = if (q.isEmpty()) allClubs else allClubs?.filter { it.name.contains(q, ignoreCase = true) }
    Column(modifier.fillMaxSize()) {
        if (!allClubs.isNullOrEmpty()) {
            val clearLabel = stringResource(R.string.np_search_clear)
            val clearButton: (@Composable () -> Unit)? = if (query.isEmpty()) null else {
                @Composable {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = clearLabel)
                    }
                }
            }
            ArenaTextField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.np_search_clubs),
                leadingIcon = Icons.Default.Search,
                trailing = clearButton,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
            )
        }
        if (clubs != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (s.offline || s.refresh.error != null) SavedDataBadge()
                LastUpdatedLabel(s.updatedAt)
            }
            if (s.refresh.error != null && !s.offline) {
                Text(
                    stringResource(R.string.f5_refresh_failed_saved),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md),
                )
            }
        }
        RefreshableList(
            refreshing = s.refresh.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when {
                clubs == null -> {
                    val err = s.refresh.error
                    when {
                        !s.cacheChecked || s.refresh.refreshing || err == null -> LoadingBox()
                        err.isConnectivity() -> NotSavedState(vm::refresh)
                        else -> ErrorState(ErrorMapper.message(err), vm::refresh)
                    }
                }
                clubs.isEmpty() && q.isNotEmpty() -> EmptyState(
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.np_no_club_match_title),
                    message = stringResource(R.string.np_no_club_match_message, q),
                )
                clubs.isEmpty() -> EmptyState(
                    icon = Icons.Default.Groups,
                    title = stringResource(R.string.f5_clubs_empty_title),
                    message = stringResource(R.string.f5_clubs_empty_message),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(clubs.distinctBy { it.clubId }, key = { "club-" + it.clubId }) { club ->
                        val cd = stringResource(R.string.f5_clubs_open_cd, club.name)
                        ArenaCard(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 72.dp)
                                .semantics(mergeDescendants = true) { contentDescription = cd },
                            onClick = { onOpenClub(club.clubId) },
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(Spacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            ) {
                                ClubLogo(club.name, resolveLogoUrl(club.logoUrl, baseUrl), 48.dp)
                                Text(
                                    club.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
