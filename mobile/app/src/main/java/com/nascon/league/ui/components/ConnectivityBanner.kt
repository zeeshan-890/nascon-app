package com.nascon.league.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.nascon.league.ui.sync.BannerKind
import com.nascon.league.ui.sync.bannerModel
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat

@Composable
fun ConnectivityBanner(
    state: ConnState,
    pendingCount: Int,
    onClick: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = bannerModel(state, pendingCount)
    if (model.kind == BannerKind.ONLINE) return

    val colors = Arena.colors
    val icon: ImageVector
    val container: Color
    val border: Color
    val content: Color
    when (model.kind) {
        BannerKind.SYNCING, BannerKind.PENDING -> {
            icon = if (model.kind == BannerKind.SYNCING) Icons.Default.Sync else Icons.Default.Schedule
            container = colors.chipBg
            border = colors.cardBorder
            content = colors.brand
        }
        BannerKind.OFFLINE -> {
            icon = Icons.Default.WifiOff
            container = colors.warnBg
            border = colors.warnBorder
            content = colors.warnFg
        }
        else -> {
            icon = Icons.Default.CloudOff
            container = colors.warnBg
            border = colors.warnBorder
            content = colors.warnFg
        }
    }
    val text = when (model.kind) {
        BannerKind.ONLINE -> stringResource(R.string.f9_banner_online)
        BannerKind.OFFLINE -> stringResource(R.string.f9_banner_offline)
        BannerKind.SERVER_UNREACHABLE -> stringResource(R.string.f9_banner_unreachable)
        BannerKind.PENDING ->
            if (model.pendingCount == 1) stringResource(R.string.f9_banner_pending_one)
            else stringResource(R.string.f9_banner_pending_many, model.pendingCount)
        BannerKind.SYNCING -> when {
            model.pendingCount == 1 -> stringResource(R.string.st_banner_syncing_one)
            model.pendingCount > 1 -> stringResource(R.string.st_banner_syncing_count, model.pendingCount)
            else -> stringResource(R.string.f9_banner_syncing)
        }
    }
    val openLabel = stringResource(R.string.f9_banner_open_status)

    val angle = if (state == ConnState.SYNCING) {
        rememberInfiniteTransition(label = "sync").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
            label = "syncAngle",
        ).value
    } else {
        0f
    }

    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .padding(start = 24.dp, end = 24.dp, top = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(container)
            .border(1.dp, border, shape)
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp).rotate(angle))
            Text(
                text,
                color = content,
                fontFamily = Montserrat,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (state == ConnState.SERVER_UNREACHABLE) {
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = content),
                ) {
                    Text(
                        stringResource(R.string.f9_banner_settings),
                        fontFamily = Montserrat,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (pendingCount > 0 && model.kind != BannerKind.PENDING && model.kind != BannerKind.SYNCING) {
                Text(
                    stringResource(R.string.f9_banner_trailing_pending, pendingCount),
                    color = content,
                    fontFamily = Montserrat,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (model.kind == BannerKind.SYNCING) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = colors.brand,
                trackColor = colors.cardBorder,
            )
        }
    }
}
