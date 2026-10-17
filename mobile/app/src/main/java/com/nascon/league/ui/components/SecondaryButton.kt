package com.nascon.league.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    val colors = Arena.colors
    val shape = RoundedCornerShape(50)
    val tone = when {
        !enabled -> colors.inkSoft
        destructive -> colors.liveFg
        else -> colors.brand
    }
    val borderColor = when {
        !enabled -> colors.cardBorder
        destructive -> colors.liveFg
        else -> colors.fieldBorder
    }
    Row(
        modifier = modifier
            .clip(shape)
            .background(Color.White)
            .border(BorderStroke(2.dp, borderColor), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 54.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tone)
        Text(text, color = tone, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
