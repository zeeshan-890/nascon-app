package com.nascon.league.ui.useradmin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nascon.league.R
import com.nascon.league.data.useradmin.UserFormError
import com.nascon.league.domain.model.Role
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat

fun roleIcon(role: Role): ImageVector = when (role) {
    Role.SUPER_ADMIN -> Icons.Default.AdminPanelSettings
    Role.LEAGUE_ORGANIZER -> Icons.Default.EmojiEvents
    Role.CLUB_MANAGER -> Icons.Default.Groups
    Role.PLAYER -> Icons.Default.SportsSoccer
    Role.VIEWER -> Icons.Default.Visibility
}

@Composable
fun UaRoleChip(role: Role, modifier: Modifier = Modifier) =
    StatusChip(text = role.label, icon = roleIcon(role), modifier = modifier, tone = ChipTone.Brand)

@Composable
fun UaStatusChip(active: Boolean, modifier: Modifier = Modifier) {
    if (active) {
        StatusChip(stringResource(R.string.ua_status_active), Icons.Default.CheckCircle, modifier, ChipTone.Success)
    } else {
        StatusChip(stringResource(R.string.ua_status_deactivated), Icons.Default.Block, modifier, ChipTone.Warning)
    }
}

@Composable
fun uaFormErrorText(e: UserFormError): String = stringResource(
    when (e) {
        UserFormError.NAME_REQUIRED -> R.string.ua_err_name
        UserFormError.EMAIL_REQUIRED -> R.string.ua_err_email_required
        UserFormError.EMAIL_INVALID -> R.string.ua_err_email_invalid
        UserFormError.EMAIL_TAKEN -> R.string.ua_err_email_taken
        UserFormError.PASSWORD_SHORT -> R.string.ua_err_password
        UserFormError.ROLE_UNKNOWN -> R.string.ua_err_role
        UserFormError.CLUB_REQUIRED -> R.string.ua_err_club
        UserFormError.LEAGUE_REQUIRED -> R.string.ua_err_league
    },
)

/** Label on the left, value on the right (G03 rows). */
@Composable
fun UaInfoRow(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    val c = Arena.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White)
            .border(1.dp, c.cardBorder, shape)
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, color = c.inkSoft, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { value() }
    }
}

@Composable
fun UaInfoText(text: String) {
    Text(
        text, color = Arena.colors.ink, fontFamily = Montserrat, fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
    )
}

/** Dropdown field in the same pill style as [com.nascon.league.ui.components.ArenaTextField]. */
@Composable
fun UaPickerField(
    label: String,
    selectedText: String?,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    errorText: String? = null,
    enabled: Boolean = true,
) {
    val c = Arena.colors
    var open by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(26.dp)
    val borderColor = if (errorText != null) c.liveFg else c.fieldBorder
    val choose = stringResource(R.string.ua_choose_one)
    Column(modifier) {
        Text(
            label, color = c.ink, fontFamily = Montserrat, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(shape)
                    .background(Color.White)
                    .border(2.dp, borderColor, shape)
                    .clickable(enabled = enabled, role = SemanticsRole.DropdownList) { open = true }
                    .semantics { if (errorText != null) error(errorText) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    selectedText ?: choose,
                    color = if (selectedText == null) c.inkSoft else c.ink,
                    fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Default.ExpandMore, contentDescription = null, tint = c.inkSoft)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                        onClick = { open = false; onSelect(value) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        if (errorText != null) {
            Row(Modifier.padding(start = 4.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = c.liveFg)
                Text(errorText, color = c.liveFg, fontFamily = Montserrat, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Selectable role option (G05): radio dot plus a check mark and text, never colour alone. */
@Composable
fun UaRoleOption(role: Role, selected: Boolean, current: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val c = Arena.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) c.chipBg else Color.White)
            .border(if (selected) 2.dp else 1.5.dp, if (selected) c.brand else c.cardBorder, shape)
            .selectable(selected = selected, enabled = enabled, role = SemanticsRole.RadioButton, onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(roleIcon(role), contentDescription = null, tint = c.brand)
        Column(Modifier.weight(1f)) {
            Text(role.label, color = c.ink, fontFamily = Montserrat, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (current) {
                Text(
                    stringResource(R.string.ua_role_current), color = c.inkSoft, fontFamily = Montserrat,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (selected) c.brand else Color.Transparent)
                .border(2.dp, c.brand, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.ua_role_selected), tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun UaGap(h: Int = 12) = Spacer(Modifier.size(width = 1.dp, height = h.dp))

@Composable
fun UaHSpace(w: Int = 10) = Spacer(Modifier.width(w.dp))

/** Shows a snackbar that survives the calling screen being popped right after. */
suspend fun androidx.compose.material3.SnackbarHostState.flash(text: String) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { showSnackbar(text) }
}
