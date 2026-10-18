package com.nascon.league.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.nascon.league.R
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat
import com.nascon.league.ui.theme.Spacing
import kotlinx.coroutines.delay

/** "m:ss" countdown text, e.g. 47 -> "0:47". Negative values clamp to 0. */
fun formatCountdown(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** Ticks down once per second from [initial]; null stays null. Restarts when [key] changes. */
@Composable
fun rememberCountdown(initial: Int?, key: Any? = initial): Int? {
    var remaining by remember(key) { mutableStateOf(initial) }
    LaunchedEffect(key) {
        while ((remaining ?: 0) > 0) {
            delay(1000)
            remaining = (remaining ?: 0) - 1
        }
    }
    return remaining
}

enum class AlertTone { ERROR, WARNING, INFO, SUCCESS }

/** Bordered inline notice (A03/A04/B26). Meaning is carried by icon + text, not colour alone. */
@Composable
fun InlineAlert(
    text: String,
    tone: AlertTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val c = Arena.colors
    val bg: Color
    val border: Color
    val fg: Color
    val defIcon: ImageVector
    val label: String
    when (tone) {
        AlertTone.ERROR -> {
            bg = c.liveBg; border = c.liveFg.copy(alpha = 0.35f); fg = c.liveFg
            defIcon = Icons.Default.ErrorOutline; label = stringResource(R.string.st_alert_error)
        }
        AlertTone.WARNING -> {
            bg = c.warnBg; border = c.warnBorder; fg = c.warnFg
            defIcon = Icons.Default.Warning; label = stringResource(R.string.st_alert_warning)
        }
        AlertTone.INFO -> {
            bg = c.chipBg; border = c.cardBorder; fg = c.brand
            defIcon = Icons.Default.Info; label = stringResource(R.string.st_alert_info)
        }
        AlertTone.SUCCESS -> {
            bg = c.successBg; border = c.successFg.copy(alpha = 0.35f); fg = c.successFg
            defIcon = Icons.Default.CheckCircle; label = stringResource(R.string.st_alert_success)
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(icon ?: defIcon, contentDescription = label, tint = fg, modifier = Modifier.size(24.dp))
            Text(
                text,
                color = fg,
                fontFamily = Montserrat,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        content?.invoke()
    }
}

/** B26 "all synced" banner (green). */
@Composable
fun SyncedBanner(modifier: Modifier = Modifier) {
    InlineAlert(
        stringResource(R.string.st_banner_synced),
        AlertTone.SUCCESS,
        modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

@Composable
private fun CenteredState(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    message: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val c = Arena.colors
    BoundedScrollColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 40.dp, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(72.dp))
        Text(
            title,
            color = c.ink,
            fontFamily = Montserrat,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            message,
            color = c.inkSoft,
            fontFamily = Montserrat,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        content()
    }
}

/** S01: skeleton rows plus a caption. */
@Composable
fun LoadingState(message: String? = null, modifier: Modifier = Modifier) {
    val c = Arena.colors
    val caption = message ?: stringResource(R.string.st_loading_default)
    val desc = stringResource(R.string.st_loading_desc)
    val alpha by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    BoundedScrollColumn(
        modifier = modifier.semantics { contentDescription = desc; liveRegion = LiveRegionMode.Polite },
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(4) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.chipBg.copy(alpha = alpha)),
            )
        }
        Text(
            caption,
            color = c.inkSoft,
            fontFamily = Montserrat,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** S02: same look as the shared [EmptyState]; kept here so all system states live together. */
@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    CenteredState(icon, Arena.colors.brand, title, message, modifier) {
        if (actionLabel != null && onAction != null) PrimaryButton(text = actionLabel, onClick = onAction)
    }
}

/** S03. A blank [message] falls back to the mockup wording. */
@Composable
fun GenericErrorState(
    message: String = "",
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    CenteredState(
        Icons.Default.ErrorOutline,
        Arena.colors.liveFg,
        stringResource(R.string.st_error_title),
        message.ifBlank { stringResource(R.string.st_error_message) },
        modifier,
    ) {
        if (onRetry != null) PrimaryButton(text = stringResource(R.string.st_try_again), onClick = onRetry)
    }
}

/** S04: countdown chip; Try again is disabled until it reaches zero (null = no countdown known). */
@Composable
fun RateLimitedState(
    retryInSeconds: Int?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = Arena.colors
    val remaining = rememberCountdown(retryInSeconds)
    val waiting = (remaining ?: 0) > 0
    CenteredState(
        Icons.Default.Schedule,
        c.warnFg,
        stringResource(R.string.st_rate_title),
        stringResource(R.string.st_rate_message),
        modifier,
    ) {
        if (remaining != null && waiting) {
            Text(
                stringResource(R.string.st_try_again_in, formatCountdown(remaining)),
                color = c.warnFg,
                fontFamily = Montserrat,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.warnBg)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (onRetry != null) {
            PrimaryButton(text = stringResource(R.string.st_try_again), onClick = onRetry, enabled = !waiting)
        }
    }
}

/** S05. [onWorkOffline] adds the secondary "Work offline" action when provided. */
@Composable
fun ServerErrorState(
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onWorkOffline: (() -> Unit)? = null,
) {
    CenteredState(
        Icons.Default.Dns,
        Arena.colors.liveFg,
        stringResource(R.string.st_server_title),
        stringResource(R.string.st_server_message),
        modifier,
    ) {
        if (onRetry != null) PrimaryButton(text = stringResource(R.string.st_try_again), onClick = onRetry)
        if (onWorkOffline != null) SecondaryButton(text = stringResource(R.string.st_work_offline), onClick = onWorkOffline)
    }
}
