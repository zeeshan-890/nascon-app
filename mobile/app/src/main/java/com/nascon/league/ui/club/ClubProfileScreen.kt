package com.nascon.league.ui.club

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import com.nascon.league.R
import com.nascon.league.core.config.ServerConfigStore
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.data.api.dto.ClubDto
import com.nascon.league.data.api.dto.ClubStatDto
import com.nascon.league.data.api.dto.RosterPlayerDto
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.domain.model.Permissions
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.LastUpdatedLabel
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.RefreshableList
import com.nascon.league.ui.components.SavedDataBadge
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.components.tenantInitials
import com.nascon.league.ui.match.isConnectivity
import com.nascon.league.ui.match.isForbidden
import com.nascon.league.ui.match.isNotFound
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun ClubProfileScreen(
    clubId: String,
    onManageRoster: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm: ClubProfileViewModel = viewModel(
        key = "club-$clubId",
        factory = ClubProfileViewModel.factory(container, clubId),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    val baseUrl by container.configStore.baseUrl.collectAsStateWithLifecycle(
        initialValue = ServerConfigStore.DEFAULT_BASE_URL,
    )
    val userId = session?.user?.userId
    val pendingFor: (String) -> Flow<Boolean> = remember(userId) {
        { playerId ->
            container.pendingRepository.observeForEntity(playerId)
                .map { showPendingChip(it, userId) }
                .distinctUntilChanged()
        }
    }
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val club = s.club
    Box(modifier.fillMaxSize()) {
        if (club == null) {
            val err = s.refresh.error
            when {
                !s.cacheChecked || s.refresh.refreshing || err == null -> LoadingBox()
                err.isNotFound() -> EmptyState(
                    icon = Icons.Default.EventBusy,
                    title = stringResource(R.string.f5_gone_title),
                    message = stringResource(R.string.f5_gone_message),
                    actionLabel = stringResource(R.string.f5_back),
                    onAction = { back?.onBackPressed() },
                )
                err.isForbidden() -> EmptyState(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.f5_forbidden_title),
                    message = stringResource(R.string.f5_forbidden_message),
                    actionLabel = stringResource(R.string.f5_back),
                    onAction = { back?.onBackPressed() },
                )
                err.isConnectivity() -> NotSavedState(vm::refresh)
                else -> ErrorState(ErrorMapper.message(err), vm::refresh)
            }
        } else {
            RefreshableList(
                refreshing = s.refresh.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                ProfileList(
                    club = club,
                    state = s,
                    baseUrl = baseUrl,
                    tenantName = session?.tenant?.name,
                    canManage = Permissions(session).canManageRoster(club.clubId),
                    pendingFor = pendingFor,
                    onManageRoster = onManageRoster,
                )
            }
        }
    }
}

@Composable
internal fun LoadingBox() {
    val cd = stringResource(R.string.f5_loading_cd)
    Box(Modifier.fillMaxSize().semantics { contentDescription = cd }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun NotSavedState(onRetry: () -> Unit) {
    EmptyState(
        icon = Icons.Default.CloudOff,
        title = stringResource(R.string.f3_not_saved_title),
        message = stringResource(R.string.f3_not_saved_message),
        actionLabel = stringResource(R.string.f5_try_again),
        onAction = onRetry,
    )
}

@Composable
internal fun ClubLogo(name: String, logoUrl: String?, size: Dp, modifier: Modifier = Modifier) {
    val initials = tenantInitials(name)
    val shape = RoundedCornerShape(12.dp)
    val placeholder: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.size(size),
            shape = shape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    initials,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = (size.value * 0.35f).coerceAtLeast(14f).sp,
                    maxLines = 1,
                )
            }
        }
    }
    val cd = stringResource(R.string.f5_logo_cd, name)
    Box(modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
        if (logoUrl == null) {
            placeholder()
        } else {
            SubcomposeAsyncImage(
                model = logoUrl,
                contentDescription = cd,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
                loading = { placeholder() },
                error = { placeholder() },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ProfileList(
    club: ClubDto,
    state: ClubProfileState,
    baseUrl: String,
    tenantName: String?,
    canManage: Boolean,
    pendingFor: (String) -> Flow<Boolean>,
    onManageRoster: (String) -> Unit,
) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var squadChoice by rememberSaveable(club.clubId) { mutableStateOf<String?>(null) }
    val squadId = squadChoice?.takeIf { id -> club.squads.any { it.squadId == id } }
    val groups = remember(club.roster, squadId) { groupRosterByPosition(club.roster, squadId) }
    val socials = remember(club.socials) { socialLinks(club.socials) }

    val noMaps = stringResource(R.string.f5_no_maps_app)
    val linkFailed = stringResource(R.string.f5_social_open_failed)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // ---- header ----
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    ClubLogo(club.name, resolveLogoUrl(club.logoUrl, baseUrl), 72.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            club.name,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (!tenantName.isNullOrBlank()) {
                            Text(
                                tenantName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (state.offline || state.refresh.error != null) SavedDataBadge()
                    LastUpdatedLabel(state.updatedAt)
                }
                if (state.refresh.error != null && !state.offline) {
                    Text(
                        stringResource(R.string.f5_refresh_failed_saved),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }
        }

        // ---- venue ----
        stickyHeader(key = "h-venue") { SectionHeader(stringResource(R.string.f5_venue_title)) }
        item(key = "venue") {
            val venue = club.venue
            val coords = formatCoordinates(venue?.lat, venue?.lng)
            OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = Spacing.md)) {
                Column(Modifier.fillMaxWidth().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (venue == null || (venue.name.isBlank() && coords == null)) {
                        Text(stringResource(R.string.f5_venue_none), style = MaterialTheme.typography.bodyLarge)
                    } else {
                        if (venue.name.isNotBlank()) {
                            Text(venue.name, style = MaterialTheme.typography.titleMedium)
                        }
                        if (coords != null) {
                            val coordsCd = stringResource(R.string.f5_venue_coordinates_cd, coords)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text(
                                    coords,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.semantics { contentDescription = coordsCd },
                                )
                            }
                            val geo = buildGeoUri(venue.lat, venue.lng, venue.name)
                            if (geo != null) {
                                val mapsCd = stringResource(
                                    R.string.f5_open_maps_cd,
                                    venue.name.ifBlank { coords },
                                )
                                OutlinedButton(
                                    onClick = { launchView(context, geo) { notify(scope, snackbar, noMaps) } },
                                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = mapsCd },
                                ) {
                                    Text(stringResource(R.string.f5_open_maps))
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- socials ----
        if (socials.isNotEmpty()) {
            stickyHeader(key = "h-socials") { SectionHeader(stringResource(R.string.f5_socials_title)) }
            items(socials, key = { "social-" + it.key }) { link ->
                SocialRow(link) { url -> launchView(context, url) { notify(scope, snackbar, linkFailed) } }
            }
        }

        // ---- staff ----
        stickyHeader(key = "h-staff") { SectionHeader(stringResource(R.string.f5_staff_title)) }
        if (club.staff.isEmpty()) {
            item(key = "staff-empty") { BodyNote(stringResource(R.string.f5_staff_empty)) }
        } else {
            items(club.staff.indices.toList(), key = { "staff-$it" }) { i ->
                val member = club.staff[i]
                val role = staffRoleLabel(member.role)
                val roleText = when (role.known) {
                    StaffRoleKind.COACH -> stringResource(R.string.f5_staff_coach)
                    StaffRoleKind.MANAGER -> stringResource(R.string.f5_staff_manager)
                    StaffRoleKind.PHYSIO -> stringResource(R.string.f5_staff_physio)
                    null -> role.text
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = Spacing.md)
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(member.name, style = MaterialTheme.typography.bodyLarge)
                        if (roleText.isNotBlank()) {
                            Text(
                                roleText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ---- season stats ----
        stickyHeader(key = "h-stats") { SectionHeader(stringResource(R.string.f5_stats_title)) }
        item(key = "stats-caption") { BodyNote(stringResource(R.string.f5_stats_caption)) }
        if (club.stats.isEmpty()) {
            item(key = "stats-empty") { BodyNote(stringResource(R.string.f5_stats_empty)) }
        } else {
            item(key = "stats-table") { StatsTable(club.stats) }
        }

        // ---- roster ----
        stickyHeader(key = "h-roster") { SectionHeader(stringResource(R.string.f5_roster_title)) }
        if (canManage) {
            item(key = "manage") {
                PrimaryButton(
                    text = stringResource(R.string.f5_manage_roster),
                    onClick = { onManageRoster(club.clubId) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                )
            }
        }
        if (club.squads.size > 1) {
            item(key = "squad-filter") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SquadChip(stringResource(R.string.f5_squad_all), squadId == null) { squadChoice = null }
                    club.squads.forEach { sq ->
                        SquadChip(sq.name, squadId == sq.squadId) { squadChoice = sq.squadId }
                    }
                }
            }
        }
        if (club.roster.isEmpty()) {
            item(key = "roster-empty") { BodyNote(stringResource(R.string.f5_roster_empty)) }
        } else if (groups.isEmpty()) {
            item(key = "roster-none-squad") { BodyNote(stringResource(R.string.f5_roster_none_in_squad)) }
        } else {
            groups.forEachIndexed { gi, group ->
                item(key = "pos-$gi") {
                    val title = if (group.isUnknown) stringResource(R.string.f5_position_unknown) else group.position
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                            .semantics { heading() },
                    )
                }
                items(group.players, key = { "pl-" + it.playerId }) { p ->
                    val pending by produceState(false, p.playerId) {
                        pendingFor(p.playerId).collect { value = it }
                    }
                    PlayerRow(p, pending)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .semantics { heading() },
        )
    }
}

@Composable
private fun BodyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
    )
}

@Composable
private fun SquadChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val selectedCd = stringResource(R.string.f5_squad_selected_cd, label)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else null,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { if (selected) contentDescription = selectedCd },
    )
}

@Composable
private fun SocialRow(link: SocialLink, onOpen: (String) -> Unit) {
    val cd = stringResource(R.string.f5_social_cd, link.label, link.value)
    val openLabel = stringResource(R.string.f5_social_open_label)
    val base = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
    val clickable = if (link.url != null) {
        base.clickable(onClickLabel = openLabel) { onOpen(link.url) }
    } else {
        base
    }
    Row(
        clickable
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .semantics(mergeDescendants = true) { contentDescription = cd },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            if (link.url != null) Icons.Default.Language else Icons.Default.AlternateEmail,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(link.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                link.value,
                style = MaterialTheme.typography.bodyLarge,
                color = if (link.url != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val SeasonWidth = 96.dp
private val NumWidth = 56.dp
private val WideNumWidth = 84.dp

@Composable
private fun StatsTable(stats: List<ClubStatDto>) {
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md),
    ) {
        StatsRow(
            listOf(
                stringResource(R.string.f5_col_season), stringResource(R.string.f5_col_played),
                stringResource(R.string.f5_col_won), stringResource(R.string.f5_col_drawn),
                stringResource(R.string.f5_col_lost), stringResource(R.string.f5_col_for),
                stringResource(R.string.f5_col_against),
            ),
            header = true,
            cd = null,
        )
        HorizontalDivider()
        stats.forEach { st ->
            val cd = stringResource(
                R.string.f5_stats_row_cd, st.season, st.played, st.won, st.drawn, st.lost, st.scored, st.conceded,
            )
            StatsRow(
                listOf(
                    st.season, st.played.toString(), st.won.toString(), st.drawn.toString(),
                    st.lost.toString(), st.scored.toString(), st.conceded.toString(),
                ),
                header = false,
                cd = cd,
            )
        }
    }
}

@Composable
private fun StatsRow(cells: List<String>, header: Boolean, cd: String?) {
    val style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyLarge
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val widths = listOf(SeasonWidth, NumWidth, NumWidth, NumWidth, NumWidth, WideNumWidth, WideNumWidth)
        .map { it * fontScale }
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .then(if (cd != null) Modifier.clearAndSetSemantics { contentDescription = cd } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { i, text ->
            Text(
                text,
                style = style,
                textAlign = if (i == 0) TextAlign.Start else TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(widths[i]).padding(horizontal = Spacing.xs),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerRow(p: RosterPlayerDto, pending: Boolean) {
    val role = playerRoleLabel(p.role)
    val roleText: String? = when (role.kind) {
        PlayerRoleKind.CAPTAIN -> stringResource(R.string.f5_role_captain)
        PlayerRoleKind.VICE_CAPTAIN -> stringResource(R.string.f5_role_vice_captain)
        PlayerRoleKind.STARTER -> stringResource(R.string.f5_role_starter)
        PlayerRoleKind.SUBSTITUTE -> stringResource(R.string.f5_role_substitute)
        PlayerRoleKind.OTHER -> role.text
        PlayerRoleKind.NONE -> null
    }
    val roleIcon: ImageVector = when (role.kind) {
        PlayerRoleKind.CAPTAIN -> Icons.Default.Star
        PlayerRoleKind.VICE_CAPTAIN -> Icons.Default.StarBorder
        PlayerRoleKind.STARTER -> Icons.Default.Check
        PlayerRoleKind.SUBSTITUTE -> Icons.Default.SwapVert
        else -> Icons.Default.Person
    }
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
            .semantics(mergeDescendants = true) {},
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p.position.isNotBlank()) {
                Text(
                    p.position,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (roleText != null || p.onLoan || pending) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    if (roleText != null) StatusChip(roleText, roleIcon)
                    if (p.onLoan) StatusChip(stringResource(R.string.f5_on_loan), Icons.Default.SyncAlt)
                    if (pending) StatusChip(stringResource(R.string.f5_pending), Icons.Default.Schedule)
                }
            }
        }
    }
}

private fun launchView(context: android.content.Context, uri: String, onFail: () -> Unit) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {
        onFail()
    }
}

private fun notify(scope: CoroutineScope, host: SnackbarHostState, message: String) {
    scope.launch { host.showSnackbar(message) }
}
