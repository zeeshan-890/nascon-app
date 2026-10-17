package com.nascon.league.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nascon.league.core.network.ConnState
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
    val icon: ImageVector
    val text: String
    val container: Color
    val content: Color
    when (state) {
        ConnState.ONLINE -> {
            icon = Icons.Default.CloudDone
            text = "Online"
            container = scheme.surfaceVariant
            content = scheme.onSurfaceVariant
        }
        ConnState.OFFLINE -> {
            icon = Icons.Default.CloudOff
            text = "Offline — showing saved data"
            container = scheme.errorContainer
            content = scheme.onErrorContainer
        }
        ConnState.SERVER_UNREACHABLE -> {
            icon = Icons.Default.ErrorOutline
            text = "Server unreachable — showing saved data"
            container = scheme.errorContainer
            content = scheme.onErrorContainer
        }
        ConnState.SYNCING -> {
            icon = Icons.Default.Sync
            text = "Syncing…"
            container = scheme.secondaryContainer
            content = scheme.onSecondaryContainer
        }
    }

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
            .clickable(onClickLabel = "Open sync status", onClick = onClick),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier
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
                    Text("Settings")
                }
            }
            if (pendingCount > 0) {
                Text("$pendingCount pending", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
