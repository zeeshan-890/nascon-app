package com.nascon.league.ui.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "scoreboard") {
            // Optimistic score: server score plus queued score events, marked "Pending".
            val shown = if (sc.hasPending) {
                m.copy(home = m.home.copy(score = sc.homeScore), away = m.away.copy(score = sc.awayScore))
            } else {
                m
            }
            Scoreboard(s, shown, Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), pendingScore = sc.hasPending)
        }
        item(key = "strip") { StatusStrip(s) }
        if (surfaceFor(sc.showPanel, sc.mode, embedded = true) != ScoringSurface.NONE) {
            item(key = "scoring-panel") { ScoringPanel(scoring) }
        }
        if (m.pinnedProducts.isNotEmpty()) {
            item(key = "products") { ProductsRow(m.pinnedProducts, onOpenProduct) }
        }
        item(key = "timeline-title") {
            Text(
                stringResource(R.string.f3_timeline_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(horizontal = Spacing.md)
                    .semantics { heading() },
            )
        }
        if (s.timeline.isEmpty()) {
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
    val cd = buildString {
        append(stringResource(R.string.f3_score_cd, homeName, home.main, awayName, away.main, statusText))
        if (outcomeStr != null) append(". ").append(outcomeStr)
        if (tiebreakStr != null) append(". ").append(tiebreakStr)
    }
    Card(modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = cd }) {
        Column(
            Modifier.fillMaxWidth().padding(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FixtureStatusChip(kind)
                if (pendingScore) StatusChip(stringResource(R.string.f3_chip_pending), Icons.Default.Schedule)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                TeamColumn(homeName, home, Modifier.weight(1f))
                TeamColumn(awayName, away, Modifier.weight(1f))
            }
            if (outcomeStr != null) {
                Text(
                    outcomeStr,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
            if (tiebreakStr != null) {
                Text(tiebreakStr, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
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
private fun TeamColumn(name: String, score: ScoreText, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            score.main,
            style = if (score.main.length > 4) ScoreTextStyle.copy(fontSize = 36.sp) else ScoreTextStyle,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        if (score.overs != null) {
            Text(
                stringResource(R.string.f3_overs, score.overs),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (score.fairPlay != 0) {
            Text(
                stringResource(R.string.f3_fair_play, score.fairPlay),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            style = MaterialTheme.typography.titleMedium,
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
    OutlinedCard(Modifier.width(180.dp)) {
        Column(Modifier.padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
                style = MaterialTheme.typography.titleMedium,
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
internal fun TimelineRow(item: TimelineItem, modifier: Modifier = Modifier, onUndo: (() -> Unit)? = null) {
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
    OutlinedCard(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = cd }) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (seq != null) {
                Text(
                    seq,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 48.dp).padding(end = Spacing.sm),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (item.compensated) TextDecoration.LineThrough else null,
                )
                if (item.pending || item.compensated) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (item.pending) StatusChip(pendingText, Icons.Default.Schedule)
                        if (item.compensated) StatusChip(correctedText, Icons.Default.CheckCircle)
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
