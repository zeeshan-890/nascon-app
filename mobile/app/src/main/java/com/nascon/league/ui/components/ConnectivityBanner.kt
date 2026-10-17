package com.nascon.league.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nascon.league.R
import com.nascon.league.core.network.ConnState
import com.nascon.league.ui.sync.BannerKind
import com.nascon.league.ui.sync.bannerModel
import com.nascon.league.ui.theme.Spacing

@Composable
fun ConnectivityBanner(
    state: ConnState,
    pendingCount: Int,
    onClick: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val model = bannerModel(state, pendingCount)
    val icon: ImageVector
    val container: Color
    val content: Color
    when (model.kind) {
        BannerKind.ONLINE -> {
            icon = Icons.Default.CloudDone
            container = scheme.surfaceVariant
            content = scheme.onSurfaceVariant
        }
        BannerKind.PENDING -> {
            icon = Icons.Default.Sync
            container = scheme.secondaryContainer
            content = scheme.onSecondaryContainer
        }
        BannerKind.OFFLINE -> {
            icon = Icons.Default.CloudOff
            container = scheme.errorContainer
            content = scheme.onErrorContainer
        }
        BannerKind.SERVER_UNREACHABLE -> {
            icon = Icons.Default.ErrorOutline
            container = scheme.errorContainer
            content = scheme.onErrorContainer
        }
        BannerKind.SYNCING -> {
            icon = Icons.Default.Sync
            container = scheme.secondaryContainer
            content = scheme.onSecondaryContainer
        }
    }
    val text = when (model.kind) {
        BannerKind.ONLINE -> stringResource(R.string.f9_banner_online)
        BannerKind.OFFLINE -> stringResource(R.string.f9_banner_offline)
        BannerKind.SERVER_UNREACHABLE -> stringResource(R.string.f9_banner_unreachable)
        BannerKind.PENDING ->
            if (model.pendingCount == 1) stringResource(R.string.f9_banner_pending_one)
            else stringResource(R.string.f9_banner_pending_many, model.pendingCount)
        BannerKind.SYNCING -> stringResource(R.string.f9_banner_syncing)
    }
    val openLabel = stringResource(R.string.f9_banner_open_status)

    val angle = if (state == ConnState.SYNCING) {
        val transition = rememberInfiniteTransition(label = "sync")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
            label = "syncAngle",
        ).value
    } else {
        0f
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = openLabel, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        color = container,
        contentColor = content,
    ) {
        Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp).rotate(angle))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (state == ConnState.SERVER_UNREACHABLE) {
                TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.f9_banner_settings))
                }
            }
            if (pendingCount > 0 && model.kind != BannerKind.PENDING) {
                Text(stringResource(R.string.f9_banner_trailing_pending, pendingCount), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (model.kind == BannerKind.SYNCING) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        }
    }
}
