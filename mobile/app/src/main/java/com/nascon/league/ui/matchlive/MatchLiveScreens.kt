package com.nascon.league.ui.matchlive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascon.league.R
import com.nascon.league.deeplink.MatchScreen
import com.nascon.league.matchlive.Countdown
import com.nascon.league.matchlive.LoadPhase
import com.nascon.league.matchlive.MatchLiveEvent
import com.nascon.league.matchlive.MatchLiveLogic
import com.nascon.league.matchlive.MatchLiveTags
import com.nascon.league.matchlive.MatchLiveUiState
import com.nascon.league.matchlive.MatchLiveViewModel
import com.nascon.league.matchlive.MatchSnapshot
import com.nascon.league.matchlive.MerchItem
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.deeplink.NotFoundScreen
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class MatchLiveActions(
    val onRetry: () -> Unit,
    val onSendChat: (String) -> Unit,
    val onAddToCart: (MerchItem) -> Unit,
    val onGoHome: () -> Unit,
    val onOpenCart: () -> Unit,
)

@Composable
fun MatchLiveRoute(vm: MatchLiveViewModel, onGoHome: () -> Unit, onSignIn: () -> Unit, onOpenCart: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.setActive(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.setActive(false) }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                MatchLiveEvent.OpenSignInSheet -> onSignIn()
                is MatchLiveEvent.ShowMessage -> snackbar.showSnackbar(event.message)
            }
        }
    }
    val actions = remember(vm, onGoHome, onOpenCart) {
        MatchLiveActions(
            onRetry = vm::retry,
            onSendChat = vm::sendChat,
            onAddToCart = vm::addToCart,
            onGoHome = onGoHome,
            onOpenCart = onOpenCart,
        )
    }
    // The tag is on the route container so it exists in every state (loading, error, not found, content).
    Box(Modifier.fillMaxSize().testTag(MatchLiveTags.LIVE_ROOT)) { MatchLiveScreen(state, actions) }
}

@Composable
fun MatchLiveScreen(state: MatchLiveUiState, actions: MatchLiveActions) {
    Column(Modifier.fillMaxSize()) {
        if (state.offline) OfflineStrip()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val snap = state.snapshot
            when {
                state.phase == LoadPhase.NOT_FOUND ->
                    NotFoundScreen(reason = "entity_missing", onRetry = actions.onRetry, onGoHome = actions.onGoHome)
                state.phase == LoadPhase.ERROR && snap == null ->
                    ErrorCard(state.errorMessage, actions.onRetry)
                snap == null -> MatchSkeleton()
                else -> {
                    if (!snap.leagueName.isNullOrBlank()) HeaderSubtitle(snap.leagueName)
                    when (state.screen) {
                        MatchScreen.LIVE, MatchScreen.LIVE_SHELL -> LiveScreen(state, snap, actions)
                        MatchScreen.PREVIEW -> PreviewScreen(state, snap, actions)
                        MatchScreen.VOD -> VodScreen(state, snap, actions)
                        MatchScreen.NOTICE -> NoticeScreen(snap, actions)
                    }
                }
            }
        }
    }
}

// ---------- Screens ----------

@Composable
private fun LiveScreen(state: MatchLiveUiState, snap: MatchSnapshot, actions: MatchLiveActions) {
    ScreenColumn(MatchLiveTags.LIVE_ROOT) {
        if (state.screen == MatchScreen.LIVE_SHELL) {
            ScoreSkeleton()
        } else {
            ScoreBlock(snap)
        }
        VideoPlayerCard(
            streamUrl = snap.streamUrl,
            posterUrl = snap.posterUrl,
            startSeconds = null,
            onRetry = actions.onRetry,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
        PinnedMerchCard(snap.pinned, state.cartCount, actions.onAddToCart, actions.onOpenCart)
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            ChatPanel(
                messages = state.chat,
                canSend = true,
                sending = state.chatSending,
                guest = state.isGuest,
                onSend = actions.onSendChat,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PreviewScreen(state: MatchLiveUiState, snap: MatchSnapshot, actions: MatchLiveActions) {
    val c = Arena.colors
    val kickoff = remember(snap.scheduledAtIso) { formatKickoff(snap.scheduledAtIso) }
    ScreenColumn(MatchLiveTags.PREVIEW_ROOT) {
        ArenaCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MatchStatusChip(snap.status)
                TeamName(snap.homeName)
                Text(stringResource(R.string.ml_vs), color = c.inkSoft, fontFamily = Montserrat, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                TeamName(snap.awayName)
                Text(
                    text = kickoff?.let { stringResource(R.string.ml_kickoff_label) + ": " + it } ?: stringResource(R.string.ml_kickoff_unknown),
                    color = c.inkMid, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                )
                state.countdown?.let { cd ->
                    Text(stringResource(R.string.ml_countdown_label), color = c.inkSoft, fontFamily = Montserrat, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(
                        countdownText(cd), color = c.brand, fontFamily = Montserrat, fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
                    )
                }
            }
        }
        PinnedMerchCard(snap.pinned, state.cartCount, actions.onAddToCart, actions.onOpenCart)
    }
}

@Composable
private fun VodScreen(state: MatchLiveUiState, snap: MatchSnapshot, actions: MatchLiveActions) {
    val c = Arena.colors
    ScreenColumn(MatchLiveTags.VOD_ROOT) {
        ScoreBlock(snap, forceCompleted = true)
        if (snap.streamUrl != null) {
            VideoPlayerCard(
                streamUrl = snap.streamUrl,
                posterUrl = snap.posterUrl,
                startSeconds = state.startSeconds,
                onRetry = actions.onRetry,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
        } else {
            ArenaCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.VideocamOff, contentDescription = null, tint = c.brand, modifier = Modifier.size(40.dp))
                    Text(stringResource(R.string.ml_vod_pending_title), color = c.ink, fontFamily = Montserrat, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.ml_vod_pending_message), color = c.inkSoft, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
            }
        }
        if (!snap.recapText.isNullOrBlank()) {
            ArenaCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ml_recap_title), color = c.ink, fontFamily = Montserrat, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text(snap.recapText, color = c.inkMid, fontFamily = Montserrat, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
            }
        }
        PinnedMerchCard(snap.pinned, state.cartCount, actions.onAddToCart, actions.onOpenCart)
    }
}

@Composable
private fun NoticeScreen(snap: MatchSnapshot, actions: MatchLiveActions) {
    val c = Arena.colors
    val kind = MatchLiveLogic.statusInfo(snap.status).kind
    val title = stringResource(
        when (kind) {
            MatchLiveLogic.StatusKind.POSTPONED -> R.string.ml_notice_postponed
            MatchLiveLogic.StatusKind.CANCELLED -> R.string.ml_notice_cancelled
            MatchLiveLogic.StatusKind.ABANDONED -> R.string.ml_notice_abandoned
            else -> R.string.ml_notice_default
        },
    )
    val icon = statusIcon(kind)
    val homeDesc = stringResource(R.string.ml_back_home_desc)
    ScreenColumn(MatchLiveTags.NOTICE_ROOT) {
        ArenaCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, contentDescription = null, tint = c.warnFg, modifier = Modifier.size(56.dp))
                Text(title, color = c.ink, fontFamily = Montserrat, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Text("${snap.homeName} ${stringResource(R.string.ml_vs)} ${snap.awayName}", color = c.inkMid, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(
                    snap.reason?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ml_notice_no_reason),
                    color = c.inkSoft, fontFamily = Montserrat, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                )
            }
        }
        PrimaryButton(
            text = stringResource(R.string.ml_back_home),
            onClick = actions.onGoHome,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = homeDesc },
        )
    }
}

// ---------- Building blocks ----------

@Composable
private fun ScreenColumn(rootTag: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(rootTag)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

@Composable
private fun ScoreBlock(snap: MatchSnapshot, forceCompleted: Boolean = false) {
    val c = Arena.colors
    val score = MatchLiveLogic.formatScore(snap.homeScore, snap.awayScore)
    val desc = stringResource(R.string.ml_score_desc, score, snap.homeName, snap.awayName)
    ArenaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MatchStatusChip(if (forceCompleted) "completed" else snap.status)
            TeamName(snap.homeName)
            Text(
                score,
                color = c.brand, fontFamily = Montserrat, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
                modifier = Modifier.testTag(MatchLiveTags.SCORE).semantics { contentDescription = desc },
            )
            TeamName(snap.awayName)
            if (!snap.minuteLabel.isNullOrBlank()) {
                Text(snap.minuteLabel, color = c.inkSoft, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ScoreSkeleton() {
    ArenaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBox(Modifier.width120().height(24.dp))
            SkeletonBox(Modifier.width120().height(18.dp))
            SkeletonBox(Modifier.width120().height(44.dp))
            SkeletonBox(Modifier.width120().height(18.dp))
        }
    }
}

private fun Modifier.width120(): Modifier = this.widthIn(min = 120.dp).fillMaxWidth(0.5f)

@Composable
private fun TeamName(name: String) {
    Text(name, color = Arena.colors.ink, fontFamily = Montserrat, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
}

@Composable
private fun MatchStatusChip(status: String?) {
    val info = MatchLiveLogic.statusInfo(status)
    val tone = when (info.kind) {
        MatchLiveLogic.StatusKind.LIVE -> ChipTone.Live
        MatchLiveLogic.StatusKind.COMPLETED -> ChipTone.Success
        MatchLiveLogic.StatusKind.POSTPONED, MatchLiveLogic.StatusKind.ABANDONED -> ChipTone.Warning
        MatchLiveLogic.StatusKind.CANCELLED -> ChipTone.Error
        else -> ChipTone.Neutral
    }
    StatusChip(
        text = info.text,
        icon = statusIcon(info.kind),
        tone = tone,
        modifier = Modifier.testTag(MatchLiveTags.STATUS),
    )
}

private fun statusIcon(kind: MatchLiveLogic.StatusKind): ImageVector = when (kind) {
    MatchLiveLogic.StatusKind.LIVE -> Icons.Default.Sensors
    MatchLiveLogic.StatusKind.UPCOMING -> Icons.Default.Schedule
    MatchLiveLogic.StatusKind.COMPLETED -> Icons.Default.CheckCircle
    MatchLiveLogic.StatusKind.POSTPONED -> Icons.Default.PauseCircle
    MatchLiveLogic.StatusKind.CANCELLED -> Icons.Default.Cancel
    MatchLiveLogic.StatusKind.ABANDONED -> Icons.Default.ReportProblem
    MatchLiveLogic.StatusKind.UNKNOWN -> Icons.Default.Info
}

@Composable
private fun countdownText(cd: Countdown): String =
    if (cd.finished) stringResource(R.string.ml_countdown_now)
    else stringResource(R.string.ml_countdown_format, cd.days, cd.hours, cd.minutes, cd.seconds)

private fun formatKickoff(iso: String?): String? {
    val millis = MatchLiveLogic.parseIsoMillis(iso) ?: return null
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))
}

@Composable
private fun OfflineStrip() {
    val c = Arena.colors
    val desc = stringResource(R.string.ml_offline_strip)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.warnBg)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(MatchLiveTags.OFFLINE_STRIP)
            .semantics(mergeDescendants = true) { contentDescription = desc },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.WifiOff, contentDescription = null, tint = c.warnFg, modifier = Modifier.size(20.dp))
        Text(desc, color = c.warnFg, fontFamily = Montserrat, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ErrorCard(message: String?, onRetry: () -> Unit) {
    val c = Arena.colors
    val retryDesc = stringResource(R.string.ml_retry_desc)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(c.liveBg)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = c.liveFg, modifier = Modifier.size(48.dp))
            Text(stringResource(R.string.ml_error_title), color = c.liveFg, fontFamily = Montserrat, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(
                message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ml_error_message),
                color = c.liveFg, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            SecondaryButton(
                text = stringResource(R.string.ml_retry),
                onClick = onRetry,
                destructive = true,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = retryDesc },
            )
        }
    }
}

@Composable
private fun SkeletonBox(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Arena.colors.chipBg))
}

@Composable
private fun MatchSkeleton() {
    val desc = stringResource(R.string.ml_loading_desc)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .semantics { contentDescription = desc },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScoreSkeleton()
        SkeletonBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        SkeletonBox(Modifier.fillMaxWidth().height(96.dp))
        SkeletonBox(Modifier.fillMaxWidth().height(160.dp))
    }
}

// ---------- Pinned merch ----------

@Composable
fun PinnedMerchCard(items: List<MerchItem>, cartCount: Int, onAddToCart: (MerchItem) -> Unit, onOpenCart: () -> Unit) {
    if (items.isEmpty()) return
    val c = Arena.colors
    val cartDesc = stringResource(R.string.ml_cart_desc, cartCount)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.ml_merch_title), color = c.ink, fontFamily = Montserrat, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() },
            )
            Row(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .heightIn(min = 48.dp)
                    .widthIn(min = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(role = Role.Button, onClick = onOpenCart)
                    .padding(horizontal = 8.dp)
                    .semantics(mergeDescendants = true) { contentDescription = cartDesc },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = c.brand, modifier = Modifier.size(24.dp))
                Box(
                    modifier = Modifier.clip(CircleShape).background(c.chipBg).widthIn(min = 28.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        cartCount.toString(), color = c.brand, fontFamily = Montserrat, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.testTag(MatchLiveTags.GUEST_CART_COUNT),
                    )
                }
            }
        }
        items.forEach { item ->
            val addDesc = stringResource(R.string.ml_add_to_cart_desc, item.name)
            ArenaCard(Modifier.fillMaxWidth()) {
                Text(item.name, color = c.ink, fontFamily = Montserrat, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(MatchLiveLogic.formatPrice(item.priceMinor), color = c.inkMid, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = stringResource(R.string.ml_add_to_cart),
                    onClick = { onAddToCart(item) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = addDesc },
                )
            }
        }
    }
}
