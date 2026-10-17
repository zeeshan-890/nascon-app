package com.nascon.league.ui.placeholder

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nascon.league.ui.components.EmptyState

@Composable
fun ComingSoonScreen(
    title: String,
    modifier: Modifier = Modifier,
    message: String = "This part is coming soon.",
) {
    EmptyState(
        icon = Icons.Default.Construction,
        title = title,
        message = message,
        modifier = modifier,
    )
}
