package com.nascon.league.ui.matchlive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.nascon.league.R
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat

/** 16:9 match video card: HLS/any URL via ExoPlayer, muted autoplay, with placeholder and error states. */
@Composable
fun VideoPlayerCard(
    streamUrl: String?,
    posterUrl: String?,
    startSeconds: Int?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Arena.colors
    val shape = RoundedCornerShape(22.dp)
    val cardDescription = stringResource(R.string.vid_card_description)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(colors.plum)
            .border(1.dp, colors.cardBorder, shape)
            .semantics { contentDescription = cardDescription },
    ) {
        if (streamUrl.isNullOrBlank()) {
            Placeholder(posterUrl = posterUrl, onRetry = onRetry)
        } else {
            PlayerSurface(
                streamUrl = streamUrl,
                posterUrl = posterUrl,
                startSeconds = startSeconds,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun Placeholder(posterUrl: String?, onRetry: () -> Unit) {
    val colors = Arena.colors
    Box(modifier = Modifier.fillMaxSize()) {
        if (!posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.3f),
            )
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Icon(Icons.Filled.Videocam, contentDescription = null, tint = colors.bg, modifier = Modifier.size(32.dp))
            Text(
                stringResource(R.string.vid_starting_soon),
                color = colors.bg,
                fontFamily = Montserrat,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            SecondaryButton(text = stringResource(R.string.vid_retry), onClick = onRetry)
        }
    }
}

@Composable
private fun PlayerSurface(
    streamUrl: String,
    posterUrl: String?,
    startSeconds: Int?,
    onRetry: () -> Unit,
) {
    val colors = Arena.colors
    val context = LocalContext.current
    var hasError by remember(streamUrl) { mutableStateOf(false) }
    var buffering by remember(streamUrl) { mutableStateOf(true) }
    var muted by remember(streamUrl) { mutableStateOf(true) }
    var wasPlaying by remember(streamUrl) { mutableStateOf(false) }

    val player = remember(streamUrl) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            volume = 0f
            playWhenReady = true
            runCatching { setMediaItem(MediaItem.fromUri(streamUrl)) }
            runCatching { prepare() }
        }
    }

    DisposableEffect(player, startSeconds) {
        var seeked = startSeconds == null || startSeconds <= 0
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY && !seeked) {
                    seeked = true
                    player.seekTo((startSeconds ?: 0) * 1000L)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                hasError = true
                buffering = false
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    if (player.isPlaying) wasPlaying = true
                    player.pause()
                }
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> {
                    if (wasPlaying && !hasError) {
                        wasPlaying = false
                        player.play()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    this.player = player
                }
            },
            update = { view -> if (view.player !== player) view.player = player },
            onRelease = { view -> view.player = null },
        )

        if (buffering && !hasError) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = colors.bg,
                )
                Text(
                    stringResource(R.string.vid_buffering),
                    color = colors.bg,
                    fontFamily = Montserrat,
                    fontSize = 12.sp,
                )
            }
        }

        if (!hasError) {
            IconButton(
                onClick = {
                    muted = !muted
                    player.volume = if (muted) 0f else 1f
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(48.dp),
            ) {
                Icon(
                    if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(if (muted) R.string.vid_unmute else R.string.vid_mute),
                    tint = colors.bg,
                )
            }
        }

        if (hasError) {
            Box(modifier = Modifier.fillMaxSize().background(colors.plum)) {
                Placeholder(
                    posterUrl = posterUrl,
                    onRetry = {
                        hasError = false
                        buffering = true
                        runCatching {
                            player.setMediaItem(MediaItem.fromUri(streamUrl))
                            player.prepare()
                            player.playWhenReady = true
                        }
                        onRetry()
                    },
                )
            }
        }
    }
}
