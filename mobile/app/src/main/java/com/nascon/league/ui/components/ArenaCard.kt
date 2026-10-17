package com.nascon.league.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nascon.league.ui.theme.Arena

@Composable
fun ArenaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Arena.colors
    val shape = RoundedCornerShape(22.dp)
    val shadowColor = colors.brandDeep.copy(alpha = 0.08f)
    Column(
        modifier = modifier
            .shadow(2.dp, shape, ambientColor = shadowColor, spotColor = shadowColor)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, colors.cardBorder, shape)
            .then(
                if (onClick != null) Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
                else Modifier,
            )
            .padding(16.dp),
        content = content,
    )
}
