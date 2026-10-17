package com.nascon.league.ui.match

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import com.nascon.league.ui.fixtures.StatusKind
import com.nascon.league.ui.fixtures.FixtureTime
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.navigation.HeaderExtra
import com.nascon.league.ui.theme.Arena
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nascon.league.R
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.data.api.dto.MatchDetailDto
import com.nascon.league.data.api.dto.PinnedProductDto
import com.nascon.league.data.api.dto.TeamScoreDto
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.LastUpdatedLabel
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.RefreshableList
import com.nascon.league.ui.components.SavedDataBadge
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.fixtures.FixtureStatusChip
import com.nascon.league.ui.fixtures.labelRes
import com.nascon.league.ui.fixtures.statusKind
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.scoring.ScoringHost
import com.nascon.league.ui.scoring.ScoringPanel
import com.nascon.league.ui.scoring.ScoringSurface
import com.nascon.league.ui.scoring.ScoringUiState
import com.nascon.league.ui.scoring.ScoringViewModel
import com.nascon.league.ui.scoring.surfaceFor
import com.nascon.league.ui.theme.ScoreTextStyle
import com.nascon.league.ui.theme.Spacing

@Composable
fun MatchDetailScreen(
    onBack: () -> Unit,
    onOpenProduct: (String) -> Unit,
    onScore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm: MatchDetailViewModel = viewModel(factory = MatchDetailViewModel.factory(container))
    val s by vm.state.collectAsStateWithLifecycle()
    val scoring: ScoringViewModel = viewModel(
        key = "scoring:" + vm.matchId,
        factory = ScoringViewModel.factory(container, vm.matchId),
    )
    val sc by scoring.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current

    // Realtime + polling only while the screen is visible.
    LifecycleStartEffect(vm) {
        vm.setActive(true)
        onStopOrDispose { vm.setActive(false) }
    }
    LaunchedEffect(vm) {
        vm.refreshFailures.collect { snackbar.showSnackbar(ErrorMapper.message(it)) }
    }

    MatchDetailContent(
        s = s,
        onBack = onBack,
        onRetry = vm::retry,
        onOpenProduct = onOpenProduct,
        onScore = { onScore(vm.matchId) },
        scoring = scoring,
        sc = sc,
        modifier = modifier,
    )
    ScoringHost(scoring)
}

@Composable
private fun MatchDetailContent(
    s: MatchUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenProduct: (String) -> Unit,
    onScore: () -> Unit,
    scoring: ScoringViewModel,
    sc: ScoringUiState,
    modifier: Modifier = Modifier,
) {
    val m = s.match
    if (m == null) {
        Box(modifier.fillMaxSize()) {
            val err = s.refreshError
            when {
                !s.cacheChecked || s.refreshing || err == null -> {
                    val cd = stringResource(R.string.f3_loading_cd)
                    Box(Modifier.fillMaxSize().semantics { contentDescription = cd }, Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                s.notFound -> EmptyState(
                    icon = Icons.Default.EventBusy,
                    title = stringResource(R.string.f3_gone_title),
                    message = stringResource(R.string.f3_gone_message),
                    actionLabel = stringResource(R.string.f3_back),
                    onAction = onBack,
                )
                s.forbidden -> EmptyState(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.f3_forbidden_title),
                    message = stringResource(R.string.f3_forbidden_message),
                    actionLabel = stringResource(R.string.f3_back),
                    onAction = onBack,
                )
                err.isConnectivity() -> EmptyState(
                    icon = Icons.Default.CloudOff,
                    title = stringResource(R.string.f3_not_saved_title),
                    message = stringResource(R.string.f3_not_saved_message),
                    actionLabel = stringResource(R.string.f3_try_again),
                    onAction = onRetry,
                )
                else -> ErrorState(ErrorMapper.message(err), onRetry)
            }
        }
        return
    }

    // Optimistic score: server score plus queued score events, marked "Pending".
    val shown = if (sc.hasPending) {
        m.copy(home = m.home.copy(score = sc.homeScore), away = m.away.copy(score = sc.awayScore))
    } else {
        m
    }
    Scoreboard(s, shown, pendingScore = sc.hasPending)
    Column(modifier.fillMaxSize()) {
        if (s.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            RefreshableList(
                refreshing = s.refreshing,
                onRefresh = onRetry,
                modifier = Modifier.fillMaxSize(),
            ) {
                MatchBody(s, m, onOpenProduct, scoring, sc)
            }
        }
        if (s.canScore && surfaceFor(sc.showPanel, sc.mode, embedded = true) == ScoringSurface.NONE) {
            PrimaryButton(
                text = stringResource(R.string.f3_score_button),
                onClick = onScore,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            )
        }
    }
}

@Composable
private fun MatchBody(
    s: MatchUiState,
    m: MatchDetailDto,
    onOpenProduct: (String) -> Unit,
    scoring: ScoringViewModel,
    sc: ScoringUiState,
) {
    val upcoming = statusKind(m.status) == StatusKind.UPCOMING
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    openKey?.let { k ->
        s.timeline.firstOrNull { it.key == k }?.let { EventDetailSheet(it) { openKey = null } }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "strip") { StatusStrip(s) }
        if (upcoming) {
            item(key = "upcoming-info") { UpcomingInfo(s) }
        }
        if (surfaceFor(sc.showPanel, sc.mode, embedded = true) != ScoringSurface.NONE) {
            item(key = "scoring-panel") { ScoringPanel(scoring) }
        }
        if (m.pinnedProducts.isNotEmpty()) {
            item(key = "products") { ProductsRow(m.pinnedProducts, onOpenProduct) }
        }
        if (upcoming && s.timeline.isEmpty()) {
            item(key = "timeline-later") {
                Text(
                    stringResource(R.string.ms_timeline_starts_live),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Arena.colors.inkSoft,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                )
            }
        } else item(key = "timeline-title") {
            Text(
                stringResource(R.string.f3_timeline_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = Arena.colors.ink,
                modifier = Modifier
                    .padding(horizontal = Spacing.md)
                    .semantics { heading() },
            )
        }
        if (upcoming && s.timeline.isEmpty()) {
            // explained by the "timeline starts when live" note above
        } else if (s.timeline.isEmpty()) {
            item(key = "no-events") {
                Text(
                    stringResource(R.string.f3_no_events),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md),
                )
            }
        } else {
            items(s.timeline, key = { it.key }) { item ->
                val eventId = if (item.key.startsWith("e-")) item.key.removePrefix("e-") else null
                TimelineRow(
                    item,
                    Modifier.padding(horizontal = Spacing.md),
                    onClick = { openKey = item.key },
                    onUndo = if (eventId != null && eventId in sc.undoableEventIds) {
                        { scoring.requestUndo(eventId) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/**
 * Hero scoreboard. It is injected into the gradient app-shell header (white text on the gradient) and
 * emits nothing into the screen body.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Scoreboard(
    s: MatchUiState,
    m: MatchDetailDto,
    modifier: Modifier = Modifier,
    pendingScore: Boolean = false,
) {
    val kind = statusKind(m.status)
    val homeName = s.homeName ?: stringResource(R.string.f3_home_team)
    val awayName = s.awayName ?: stringResource(R.string.f3_away_team)
    val home = formatScore(m.home)
    val away = formatScore(m.away)
    val outcome = outcomeText(m)
    val outcomeStr = outcome.asString()
    val tiebreakId = tiebreakTeamId(m)
    val tiebreakStr = tiebreakId?.let { id ->
        val team = when (id) {
            m.home.teamId -> homeName
            m.away.teamId -> awayName
            else -> null
        }
        team?.let { stringResource(R.string.f3_tiebreak_won, it) }
    }
    val statusText = stringResource(kind.labelRes())
    val upcoming = kind == StatusKind.UPCOMING
    val scoreText = if (upcoming) stringResource(R.string.ms_vs) else "${home.main} – ${away.main}"
    val countdown = if (upcoming) {
        val start = remember(s.scheduledAt) { FixtureTime.parse(s.scheduledAt) }
        remember(start) { countdownTo(java.time.Instant.now(), start) }
    } else null
    val countdownStr = when (countdown) {
        null -> null
        Countdown.Soon -> stringResource(R.string.ms_starts_soon)
        is Countdown.Days -> pluralStringResource(R.plurals.ms_starts_days, countdown.n, countdown.n)
        is Countdown.Hours -> pluralStringResource(R.plurals.ms_starts_hours, countdown.n, countdown.n)
        is Countdown.Minutes -> pluralStringResource(R.plurals.ms_starts_minutes, countdown.n, countdown.n)
    }
    val cd = buildString {
        if (upcoming) {
            append(stringResource(R.string.ms_upcoming_cd, homeName, awayName, statusText))
            if (countdownStr != null) append(". ").append(countdownStr)
        } else {
            append(stringResource(R.string.f3_score_cd, homeName, home.main, awayName, away.main, statusText))
        }
        if (outcomeStr != null) append(". ").append(outcomeStr)
        if (tiebreakStr != null) append(". ").append(tiebreakStr)
    }
    val pendingText = stringResource(R.string.f3_chip_pending)
    HeaderExtra {
        Column(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = cd },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HeroTeam(homeName, home, Modifier.weight(1f))
                Text(
                    scoreText,
                    style = if (scoreText.length > 9) ScoreTextStyle.copy(fontSize = 26.sp) else ScoreTextStyle,
                    color = Color.White,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.sm),
                )
                HeroTeam(awayName, away, Modifier.weight(1f))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                FixtureStatusChip(kind)
                if (pendingScore) StatusChip(pendingText, Icons.Default.Schedule, tone = ChipTone.Warning)
            }
            if (countdownStr != null) {
                Text(
                    countdownStr,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
            if (outcomeStr != null) {
                Text(
                    outcomeStr,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
            if (tiebreakStr != null) {
                Text(
                    tiebreakStr,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun OutcomeText.asString(): String? = when (this) {
    OutcomeText.HomeWin -> stringResource(R.string.f3_outcome_home_win)
    OutcomeText.AwayWin -> stringResource(R.string.f3_outcome_away_win)
    OutcomeText.Draw -> stringResource(R.string.f3_outcome_draw)
    OutcomeText.ResultPending -> stringResource(R.string.f3_outcome_pending)
    OutcomeText.NotDecided -> stringResource(R.string.f3_outcome_not_decided)
    OutcomeText.None -> null
}

@Composable
private fun HeroTeam(name: String, score: ScoreText, modifier: Modifier = Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (score.overs != null) {
            Text(
                stringResource(R.string.f3_overs, score.overs),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
        }
        if (score.fairPlay != 0) {
            Text(
                stringResource(R.string.f3_fair_play, score.fairPlay),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusStrip(s: MatchUiState) {
    val showBadge = s.offline || s.refreshError != null
    val paused = s.isLive && !s.realtimeConnected
    if (!showBadge && s.updatedAt == null && !paused) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (showBadge) SavedDataBadge()
            LastUpdatedLabel(s.updatedAt)
        }
        if (s.offline) {
            val a = Arena.colors
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .background(a.warnBg)
                    .padding(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.CloudOff, contentDescription = null, tint = a.warnFg, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.ms_offline_banner),
                    style = MaterialTheme.typography.bodyLarge,
                    color = a.warnFg,
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
        }
        if (paused) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.f3_live_paused),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun ProductsRow(products: List<PinnedProductDto>, onOpenProduct: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            stringResource(R.string.f3_products_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = Arena.colors.ink,
            modifier = Modifier
                .padding(horizontal = Spacing.md)
                .semantics { heading() },
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(products, key = { it.productId }) { p -> ProductCard(p, onOpenProduct) }
        }
    }
}

@Composable
private fun ProductCard(p: PinnedProductDto, onOpenProduct: (String) -> Unit) {
    ArenaCard(Modifier.width(180.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
            if (p.imageUrl.isNullOrBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(MaterialTheme.shapes.small)
                        .then(Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {}
                    Icon(Icons.Default.ShoppingBag, contentDescription = null, modifier = Modifier.size(40.dp))
                }
            } else {
                AsyncImage(
                    model = p.imageUrl,
                    contentDescription = stringResource(R.string.f3_product_image_cd, p.name),
                    placeholder = placeholder,
                    error = placeholder,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(MaterialTheme.shapes.small),
                )
            }
            Text(
                p.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.f3_price, formatPriceMinor(p.priceMinor)),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = Arena.colors.brandDeep,
            )
            val buyCd = stringResource(R.string.f3_buy_cd, p.name)
            PrimaryButton(
                text = stringResource(R.string.f3_buy),
                onClick = { onOpenProduct(p.productId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = buyCd },
            )
        }
    }
}

@Composable
internal fun EventLabel.asString(): String = when (this) {
    EventLabel.MatchInit -> stringResource(R.string.f3_ev_match_init)
    EventLabel.MatchStart -> stringResource(R.string.f3_ev_match_start)
    EventLabel.MatchEnd -> stringResource(R.string.f3_ev_match_end)
    EventLabel.Unknown -> stringResource(R.string.f3_ev_unknown)
    is EventLabel.TiebreakWinner ->
        if (team != null) stringResource(R.string.f3_ev_tiebreak, team) else stringResource(R.string.f3_ev_tiebreak_noteam)
    is EventLabel.Discipline -> when {
        points != null && team != null -> stringResource(R.string.f3_ev_discipline_full, points, team)
        points != null -> stringResource(R.string.f3_ev_discipline_points, points)
        team != null -> stringResource(R.string.f3_ev_discipline_team, team)
        else -> stringResource(R.string.f3_ev_discipline)
    }
    is EventLabel.Compensation ->
        if (reason != null) stringResource(R.string.f3_ev_correction, reason) else stringResource(R.string.f3_ev_correction_plain)
    is EventLabel.Score ->
        if (team != null) stringResource(R.string.f3_ev_score_team, title, team) else title
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimelineRow(
    item: TimelineItem,
    modifier: Modifier = Modifier,
    onUndo: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val label = item.label.asString()
    val seq = item.sequence?.let { stringResource(R.string.f3_event_seq, it) }
    val pendingText = stringResource(R.string.f3_chip_pending)
    val correctedText = stringResource(R.string.f3_chip_corrected)
    val cd = buildString {
        if (seq != null) append(seq).append(", ")
        append(label)
        if (item.pending) append(", ").append(pendingText)
        if (item.compensated) append(", ").append(correctedText)
    }
    val openCd = if (onClick != null) stringResource(R.string.ms_event_open_cd, cd) else cd
    ArenaCard(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = openCd },
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (seq != null) {
                Text(
                    seq,
                    style = MaterialTheme.typography.labelLarge,
                    color = Arena.colors.brandDeep,
                    modifier = Modifier.widthIn(min = 48.dp).padding(end = Spacing.sm),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = Arena.colors.ink,
                    textDecoration = if (item.compensated) TextDecoration.LineThrough else null,
                )
                if (item.pending || item.compensated) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (item.pending) StatusChip(pendingText, Icons.Default.Schedule, tone = ChipTone.Warning)
                        if (item.compensated) StatusChip(correctedText, Icons.Default.CheckCircle, tone = ChipTone.Success)
                    }
                }
            }
            if (onUndo != null) {
                val undoCd = stringResource(R.string.f8_undo_cd, label)
                TextButton(
                    onClick = onUndo,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = undoCd },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.f8_undo), modifier = Modifier.padding(start = Spacing.xs))
                }
            }
        }
    }
}

@Composable
private fun UpcomingInfo(s: MatchUiState) {
    val tbc = stringResource(R.string.f3_time_tbc)
    val kickoff = FixtureTime.format(s.scheduledAt, fallback = tbc)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        InfoCard(stringResource(R.string.ms_kickoff), kickoff)
        s.venueName?.let { InfoCard(stringResource(R.string.ms_venue), it) }
    }
}

@Composable
private fun InfoCard(label: String, value: String) {
    ArenaCard(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Arena.colors.inkSoft, modifier = Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Arena.colors.ink,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Bottom sheet with only the fields the event actually carries. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun EventDetailSheet(item: TimelineItem, onDismiss: () -> Unit) {
    val a = Arena.colors
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val label = item.label.asString()
    val status = when {
        item.pending -> stringResource(R.string.ms_event_status_pending)
        item.compensated -> stringResource(R.string.ms_event_status_corrected)
        else -> stringResource(R.string.ms_event_status_recorded)
    }
    val statusIcon = if (item.pending) Icons.Default.Schedule else Icons.Default.CheckCircle
    val tone = when {
        item.pending -> ChipTone.Warning
        item.compensated -> ChipTone.Neutral
        else -> ChipTone.Success
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Color.White,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                stringResource(R.string.ms_event_detail_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = a.ink,
                modifier = Modifier.semantics { heading() },
            )
            InfoCard(stringResource(R.string.ms_event_field_event), label)
            item.teamName?.let { InfoCard(stringResource(R.string.ms_event_field_team), it) }
            item.minute?.let {
                InfoCard(stringResource(R.string.ms_event_field_minute), stringResource(R.string.ms_event_minute_value, it))
            }
            item.sequence?.let { InfoCard(stringResource(R.string.ms_event_field_order), stringResource(R.string.f3_event_seq, it)) }
            ArenaCard(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        stringResource(R.string.ms_event_field_status),
                        style = MaterialTheme.typography.bodyLarge,
                        color = a.inkSoft,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(status, statusIcon, tone = tone)
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.End)) {
                Text(stringResource(R.string.ms_event_close))
            }
        }
    }
}
