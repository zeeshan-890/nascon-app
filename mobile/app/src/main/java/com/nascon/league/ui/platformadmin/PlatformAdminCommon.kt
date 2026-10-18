package com.nascon.league.ui.platformadmin

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.toggleable
import com.nascon.league.R
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.FeatureUnavailableState
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.theme.Arena

/** Scrolling page body used by the simple screens: 24dp gutters like the mockups. */
@Composable
fun PaPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Form layout: scrolling fields with a pinned bottom action bar (kept in view at large font sizes). */
@Composable
fun PaFormPage(
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)) { bottomBar() }
    }
}

/** Cancel + primary action side by side; stacked when there is no room (large text). */
@Composable
fun PaButtonPair(
    secondaryText: String,
    onSecondary: () -> Unit,
    primaryText: String,
    onPrimary: () -> Unit,
    primaryLoading: Boolean = false,
    primaryEnabled: Boolean = true,
    secondaryEnabled: Boolean = true,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(secondaryText, onSecondary, modifier = Modifier.weight(1f), enabled = secondaryEnabled)
        PrimaryButton(primaryText, onPrimary, modifier = Modifier.weight(1.4f), loading = primaryLoading, enabled = primaryEnabled)
    }
}

@Composable
fun PaHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = Arena.colors.ink,
        modifier = modifier.padding(top = 4.dp).semantics { heading() },
    )
}

@Composable
fun PaNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp), color = Arena.colors.inkSoft, modifier = modifier)
}

/** One "label ... value" row card, as in the G10/G14/G16 mockups. */
@Composable
fun PaInfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    com.nascon.league.ui.components.ArenaCard(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Arena.colors.inkSoft, modifier = Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                color = Arena.colors.ink,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1.2f),
            )
        }
    }
}

/** Full-width toggle row (a switch whose whole row is the tap target). */
@Composable
fun PaSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val on = stringResource(R.string.pa_on)
    val off = stringResource(R.string.pa_off)
    com.nascon.league.ui.components.ArenaCard(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .semantics { stateDescription = if (checked) on else off },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

/** Round minus / value / plus control (G11 points, G10 roster limits). */
@Composable
fun PaStepper(
    value: Int,
    onChange: (Int) -> Unit,
    min: Int,
    max: Int,
    decreaseDescription: String,
    increaseDescription: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        StepButton(Icons.Default.Remove, decreaseDescription, enabled = value > min) { onChange(value - 1) }
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = Arena.colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 40.dp),
        )
        StepButton(Icons.Default.Add, increaseDescription, enabled = value < max) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val tone = if (enabled) Arena.colors.brand else Arena.colors.inkSoft.copy(alpha = 0.5f)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(2.dp, tone, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tone)
    }
}

/** A text-field-looking selector with a dropdown menu. [options] pair = (value, label). */
@Composable
fun <T> PaDropdownField(
    label: String,
    selectedText: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = Arena.colors.ink,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Box {
            val shape = RoundedCornerShape(26.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(shape)
                    .background(androidx.compose.ui.graphics.Color.White)
                    .border(2.dp, Arena.colors.fieldBorder, shape)
                    .clickable(role = Role.DropdownList) { open = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selectedText, style = MaterialTheme.typography.bodyLarge, color = Arena.colors.ink, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Arena.colors.brand)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
                        onClick = { open = false; onSelect(value) },
                        trailingIcon = if (text == selectedText) {
                            { Icon(Icons.Default.Check, contentDescription = null, tint = Arena.colors.brand) }
                        } else null,
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

/** Server has no such endpoint -> explain; otherwise the plain-language error with Try again. */
@Composable
fun PaFailure(failure: Failure, onRetry: (() -> Unit)?, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    if (failure.unavailable) FeatureUnavailableState(modifier, onBack) else ErrorState(failure.message, onRetry, modifier)
}

@Composable
fun fieldError(
    problem: FieldProblem?,
    @StringRes badFormat: Int = R.string.pa_err_bad_format,
    @StringRes duplicate: Int = R.string.pa_err_duplicate,
): String? = when (problem) {
    null -> null
    FieldProblem.REQUIRED -> stringResource(R.string.pa_err_required)
    FieldProblem.TOO_SHORT -> stringResource(R.string.pa_err_too_short)
    FieldProblem.TOO_LONG -> stringResource(R.string.pa_err_too_long)
    FieldProblem.BAD_FORMAT -> stringResource(badFormat)
    FieldProblem.DUPLICATE -> stringResource(duplicate)
}

/** Looks up "pa_<prefix>_<key>" (dots and dashes become underscores); falls back to a readable form of the key. */
fun lookupLabel(context: Context, prefix: String, key: String): String {
    val name = "pa_${prefix}_" + key.replace('.', '_').replace('-', '_').lowercase()
    val id = context.resources.getIdentifier(name, "string", context.packageName)
    return if (id != 0) context.getString(id) else humanizeKey(key)
}

@Composable
fun actionLabel(key: String): String = lookupLabel(LocalContext.current, "action", key)

@Composable
fun scopeLabel(scope: String): String = lookupLabel(LocalContext.current, "scope", scope)

@Composable
fun roleLabel(role: String): String = lookupLabel(LocalContext.current, "role", role)

@Composable
fun categoryLabel(category: String): String = lookupLabel(LocalContext.current, "cat", category)
