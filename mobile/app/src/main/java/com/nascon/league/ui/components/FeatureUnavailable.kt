package com.nascon.league.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nascon.league.core.network.ApiError

/** True when the connected server does not offer the endpoint (unknown route / not implemented), as opposed to a data error. */
fun ApiError.isFeatureUnavailable(): Boolean = this is ApiError.Http &&
    (status == 405 || status == 501 ||
        (status == 404 && (message.contains("route not found", ignoreCase = true) || code == "HTTP_404")))

/** Plain-language state shown instead of a screen whose server endpoint does not exist yet. */
@Composable
fun FeatureUnavailableState(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    EmptyState(
        icon = Icons.Default.CloudOff,
        title = "Not available on this server yet",
        message = "This part of the app needs a newer server. Everything else keeps working.",
        modifier = modifier,
        actionLabel = if (onBack != null) "Go back" else null,
        onAction = onBack,
    )
}
