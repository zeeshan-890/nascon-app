package com.nascon.league.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat

enum class ChipTone { Neutral, Live, Success, Warning, Error, Brand }

@Composable
fun StatusChip(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.Neutral,
) {
    val c = Arena.colors
    val bg: Color
    val fg: Color
    when (tone) {
        ChipTone.Neutral, ChipTone.Brand -> { bg = c.chipBg; fg = c.brand }
        ChipTone.Live, ChipTone.Error -> { bg = c.liveBg; fg = c.liveFg }
        ChipTone.Success -> { bg = c.successBg; fg = c.successFg }
        ChipTone.Warning -> { bg = c.warnBg; fg = c.warnFg }
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(text, color = fg, fontFamily = Montserrat, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LiveDotChip(text: String, modifier: Modifier = Modifier) {
    val c = Arena.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.liveBg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFE5271B)))
        Spacer(Modifier.width(6.dp))
        Text(text, color = c.liveFg, fontFamily = Montserrat, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
