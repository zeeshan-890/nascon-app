package com.nascon.league.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = "Cancel",
) {
    val colors = Arena.colors
    val pill = RoundedCornerShape(50)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(title, color = colors.ink, fontFamily = Montserrat, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        },
        text = {
            Text(
                message,
                color = colors.inkMid,
                fontFamily = Montserrat,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(pill)
                    .then(
                        if (destructive) Modifier.background(colors.liveFg)
                        else Modifier.background(colors.buttonBrush),
                    )
                    .clickable(role = Role.Button, onClick = onConfirm)
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(confirmLabel, color = Color.White, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(pill)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(dismissLabel, color = colors.brand, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        },
    )
}
