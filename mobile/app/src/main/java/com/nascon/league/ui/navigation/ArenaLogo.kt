package com.nascon.league.ui.navigation

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.nascon.league.R

@Composable
fun ArenaLogo(modifier: Modifier = Modifier) {
    Image(painter = painterResource(R.drawable.arena_logo), contentDescription = null, modifier = modifier)
}
