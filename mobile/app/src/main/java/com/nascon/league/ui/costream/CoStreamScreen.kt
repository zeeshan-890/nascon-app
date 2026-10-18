package com.nascon.league.ui.costream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascon.league.R
import com.nascon.league.costream.CoStreamTags
import com.nascon.league.costream.CoStreamUiState
import com.nascon.league.costream.CoStreamViewModel
import com.nascon.league.costream.TileUi
import com.nascon.league.matchlive.LoadPhase
import com.nascon.league.matchlive.MatchLiveEvent
import com.nascon.league.matchlive.MatchLiveTags
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.deeplink.NotFoundScreen
import com.nascon.league.ui.matchlive.ChatPanel
import com.nascon.league.ui.matchlive.VideoPlayerCard
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat

@Composable
fun CoStreamRoute(
    vm: CoStreamViewModel,
    onGoHome: () -> Unit,
    onSignIn: () -> Unit,
    onOpenVod: (String) -> Unit = {},
) {
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
    Box(Modifier.fillMaxSize().testTag(CoStreamTags.ROOT)) {
        CoStreamScreen(
            state = state,
            onRetry = vm::retry,
            onSendChat = vm::sendChat,
            onGoHome = onGoHome,
            onOpenVod = onOpenVod,
        )
    }
}

@Composable
fun CoStreamScreen(
    state: CoStreamUiState,
    onRetry: () -> Unit,
    onSendChat: (String) -> Unit,
    onGoHome: () -> Unit,
    onOpenVod: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        if (state.offline) OfflineStrip()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val session = state.session
            when {
                state.phase == LoadPhase.NOT_FOUND ->
                    NotFoundScreen(reason = "entity_missing", onRetry = null, onGoHome = onGoHome)
                state.phase == LoadPhase.ERROR && session == null ->
                    ErrorCard(state.errorMessage, onRetry)
                session == null -> CoStreamSkeleton()
                else -> Content(state, onRetry, onSendChat, onGoHome, onOpenVod)
            }
        }
    }
}

@Composable
private fun Content(
    state: CoStreamUiState,
    onRetry: () -> Unit,
    onSendChat: (String) -> Unit,
    onGoHome: () -> Unit,
    onOpenVod: (String) -> Unit,
) {
    val c = Arena.colors
    val session = state.session ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(CoStreamTags.ROOT)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SessionChip(state)
        VideoPlayerCard(
            streamUrl = session.playbackUrl,
            posterUrl = session.posterUrl,
            startSeconds = null,
            onRetry = onRetry,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
        state.banner?.let { Banner(it) }
        if (state.tiles.isNotEmpty()) {
            Text(
                stringResource(R.string.cs_participants_title), color = c.ink, fontFamily = Montserrat,
                fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() },
            )
            Tiles(state.tiles.take(4))
        }
        if (state.ended) {
            EndedCard(session.vodUrl, onOpenVod, onGoHome)
        }
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            ChatPanel(
                messages = state.chat,
                canSend = !state.ended,
                sending = state.chatSending,
                guest = state.isGuest,
                onSend = onSendChat,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SessionChip(state: CoStreamUiState) {
    when {
        state.ended -> StatusChip(stringResource(R.string.cs_ended), Icons.Default.CheckCircle, tone = ChipTone.Neutral)
        state.offline -> StatusChip(stringResource(R.string.cs_reconnecting), Icons.Default.Sync, tone = ChipTone.Warning)
        else -> StatusChip(stringResource(R.string.cs_live), Icons.Default.Sensors, tone = ChipTone.Live)
    }
}

@Composable
private fun Banner(text: String) {
    val c = Arena.colors
    val desc = stringResource(R.string.cs_banner_desc, text)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.warnBg)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(CoStreamTags.HANDOFF_BANNER)
            .semantics(mergeDescendants = true) {
                contentDescription = desc
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Info, contentDescription = null, tint = c.warnFg, modifier = Modifier.size(20.dp))
        Text(text, color = c.warnFg, fontFamily = Montserrat, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Tiles(tiles: List<TileUi>) {
    val single = tiles.size == 1 || tiles.any { it.large }
    if (single) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.forEachIndexed { i, t -> Tile(i, t, Modifier.fillMaxWidth()) }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(2).forEachIndexed { row, pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEachIndexed { j, t -> Tile(row * 2 + j, t, Modifier.weight(1f)) }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Tile(index: Int, tile: TileUi, modifier: Modifier) {
    val c = Arena.colors
    val desc = buildString {
        append(tile.name)
        if (tile.isHost) append(stringResource(R.string.cs_tile_desc_host))
        if (tile.muted) append(stringResource(R.string.cs_tile_desc_muted))
    }
    ArenaCard(
        modifier
            .heightIn(min = 48.dp)
            .testTag("${CoStreamTags.TILE_PREFIX}$index")
            .semantics(mergeDescendants = true) { contentDescription = desc },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tile.name, color = c.ink, fontFamily = Montserrat, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            if (tile.isHost) {
                Row(
                    Modifier.testTag(CoStreamTags.HOST_MARKER),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = c.brand, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.cs_host), color = c.brand, fontFamily = Montserrat, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (tile.muted) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.MicOff, contentDescription = null, tint = c.inkSoft, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.cs_muted), color = c.inkSoft, fontFamily = Montserrat, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun EndedCard(vodUrl: String?, onOpenVod: (String) -> Unit, onGoHome: () -> Unit) {
    val c = Arena.colors
    val vodDesc = stringResource(R.string.cs_watch_recording_desc)
    val homeDesc = stringResource(R.string.cs_go_home_desc)
    ArenaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.cs_ended), color = c.ink, fontFamily = Montserrat, fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.cs_ended_message), color = c.inkSoft, fontFamily = Montserrat, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            )
            if (vodUrl != null) {
                SecondaryButton(
                    text = stringResource(R.string.cs_watch_recording),
                    onClick = { onOpenVod(vodUrl) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = vodDesc },
                )
            }
            PrimaryButton(
                text = stringResource(R.string.cs_go_home),
                onClick = onGoHome,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = homeDesc },
            )
        }
    }
}

@Composable
private fun OfflineStrip() {
    val c = Arena.colors
    val desc = stringResource(R.string.cs_offline_strip)
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
    val retryDesc = stringResource(R.string.cs_retry_desc)
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
            Text(
                stringResource(R.string.cs_error_title), color = c.liveFg, fontFamily = Montserrat, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() },
            )
            Text(
                message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.cs_error_message),
                color = c.liveFg, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            SecondaryButton(
                text = stringResource(R.string.cs_retry),
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
private fun CoStreamSkeleton() {
    val desc = stringResource(R.string.cs_loading_desc)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .semantics { contentDescription = desc },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkeletonBox(Modifier.fillMaxWidth(0.3f).height(28.dp))
        SkeletonBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        SkeletonBox(Modifier.fillMaxWidth().height(72.dp))
        SkeletonBox(Modifier.fillMaxWidth().height(160.dp))
    }
}
