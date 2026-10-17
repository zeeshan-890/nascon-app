package com.nascon.league.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val colors = Arena.colors
    val shape = RoundedCornerShape(50)
    val contentColor = if (enabled) Color.White else colors.inkSoft
    val shadowColor = colors.brandDeep.copy(alpha = 0.35f)
    Row(
        modifier = modifier
            .then(if (enabled) Modifier.shadow(6.dp, shape, ambientColor = shadowColor, spotColor = shadowColor) else Modifier)
            .clip(shape)
            .then(if (enabled) Modifier.background(colors.buttonBrush) else Modifier.background(colors.cardBorder))
            .clickable(enabled = enabled, role = Role.Button) { if (!loading) onClick() }
            .heightIn(min = 54.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = contentColor)
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = contentColor)
        }
        Text(text, color = contentColor, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
