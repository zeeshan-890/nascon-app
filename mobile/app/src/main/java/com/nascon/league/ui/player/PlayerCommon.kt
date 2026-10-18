package com.nascon.league.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import com.nascon.league.ui.failure.userMessage
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.data.api.dto.ClubDto
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.club.NotSavedState
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.match.isConnectivity
import com.nascon.league.ui.theme.Arena
import kotlinx.coroutines.launch
import java.time.Instant

/** Snapshot of a club payload read from the local cache plus its refresh outcome. */
class ClubLoad(
    val club: ClubDto?,
    val updatedAt: Instant?,
    val checked: Boolean,
    val refreshing: Boolean,
    val error: ApiError?,
    val retry: () -> Unit,
)

/** Observes the cached club (when [clubId] is non-null) and refreshes it once on entry. */
@Composable
internal fun rememberClubLoad(clubId: String?): ClubLoad {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var checked by remember(clubId) { mutableStateOf(clubId == null) }
    var refreshing by remember(clubId) { mutableStateOf(clubId != null) }
    var error by remember(clubId) { mutableStateOf<ApiError?>(null) }
    val cached by produceState<com.nascon.league.data.repo.Cached<ClubDto>?>(null, clubId) {
        if (clubId == null) return@produceState
        container.clubRepository.observe(clubId).collect {
            value = it
            checked = true
        }
    }
    suspend fun doRefresh() {
        if (clubId == null) return
        refreshing = true
        val r = container.clubRepository.refresh(clubId)
        error = (r as? NetworkResult.Failure)?.error
        refreshing = false
    }
    LaunchedEffect(clubId) { doRefresh() }
    return ClubLoad(
        club = cached?.data,
        updatedAt = cached?.updatedAt,
        checked = checked,
        refreshing = refreshing,
        error = error,
        retry = { scope.launch { doRefresh() } },
    )
}

@Composable
internal fun LoadFailure(error: ApiError, onRetry: () -> Unit) {
    if (error.isConnectivity()) NotSavedState(onRetry) else ErrorState(userMessage(error), onRetry)
}

/** Round initials avatar. Decorative: callers describe the person in adjacent text. */
@Composable
internal fun InitialsAvatar(
    initials: String,
    size: Dp,
    modifier: Modifier = Modifier,
    onGradient: Boolean = false,
) {
    val colors = Arena.colors
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (onGradient) Color.White else colors.chipBg)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            style = MaterialTheme.typography.labelLarge,
            color = colors.brand,
            fontSize = (size.value * 0.38f).coerceAtLeast(14f).sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
    }
}

/** Squad id to display name, from a cached club payload. */
internal fun squadNames(club: ClubDto?): Map<String, String> =
    club?.squads?.associate { it.squadId to it.name }.orEmpty()
