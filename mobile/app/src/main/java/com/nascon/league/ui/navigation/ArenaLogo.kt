package com.nascon.league.ui.navigation

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.nascon.league.R

/** Dragon mark with a transparent background. White on dark (gradient) surfaces, full colour on light ones. */
@Composable
fun ArenaLogo(modifier: Modifier = Modifier, onDark: Boolean = true) {
    Image(
        painter = painterResource(if (onDark) R.drawable.arena_mark_white else R.drawable.arena_mark),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

/** Dragon mark plus the ARENA360 wordmark, transparent background. */
@Composable
fun ArenaLogoFull(modifier: Modifier = Modifier, onDark: Boolean = false) {
    Image(
        painter = painterResource(if (onDark) R.drawable.arena_logo_white else R.drawable.arena_logo),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
