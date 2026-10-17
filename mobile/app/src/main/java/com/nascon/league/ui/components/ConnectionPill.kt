package com.nascon.league.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nascon.league.R
import com.nascon.league.core.network.ConnState
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat

@Composable
fun ConnectionPill(
    state: ConnState,
    pendingCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Arena.colors
    val onHeader = state == ConnState.ONLINE || state == ConnState.SYNCING
    val bg = if (onHeader) Color.White.copy(alpha = 0.18f) else Color.White
    val fg = if (onHeader) Color.White else colors.warnFg
    val label = when (state) {
        ConnState.ONLINE -> "Online"
        ConnState.SYNCING -> "Syncing…"
        ConnState.OFFLINE -> "Offline"
        ConnState.SERVER_UNREACHABLE -> "No server"
    }
    val pending = if (pendingCount > 0) " · " + stringResource(R.string.f9_banner_trailing_pending, pendingCount) else ""
    val text = label + pending
    val openLabel = stringResource(R.string.f9_banner_open_status)

    val angle = if (state == ConnState.SYNCING) {
        rememberInfiniteTransition(label = "pillSync").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
            label = "pillSyncAngle",
        ).value
    } else {
        0f
    }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                ConnState.ONLINE -> Box(Modifier.size(10.dp).clip(CircleShape).background(colors.onlineDot))
                ConnState.SYNCING -> Icon(Icons.Default.Sync, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp).rotate(angle))
                ConnState.OFFLINE -> Icon(Icons.Default.WifiOff, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                ConnState.SERVER_UNREACHABLE -> Icon(Icons.Default.CloudOff, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(text, color = fg, fontFamily = Montserrat, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
