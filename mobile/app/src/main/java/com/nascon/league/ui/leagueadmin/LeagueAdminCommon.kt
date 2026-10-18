package com.nascon.league.ui.leagueadmin

import androidx.annotation.PluralsRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nascon.league.R
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.FeatureUnavailableState
import com.nascon.league.ui.components.GenericErrorState
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.LoadingState
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.components.isFeatureUnavailable
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun <T> LoadContent(
    state: Load<T>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        Load.Loading -> LoadingState()
        is Load.Failed ->
            if (state.error.isFeatureUnavailable()) {
                FeatureUnavailableState(onBack = onBack)
            } else {
                GenericErrorState(message = ErrorMapper.message(state.error), onRetry = onRetry)
            }
        is Load.Data -> content(state.value)
    }
}

@Composable
fun NoAccessState(onBack: () -> Unit) {
    EmptyState(
        icon = Icons.Default.Lock,
        title = stringResource(R.string.la_no_access_title),
        message = stringResource(R.string.la_no_access_message),
        actionLabel = stringResource(R.string.la_go_back),
        onAction = onBack,
    )
}

@Composable
fun plural(@PluralsRes id: Int, n: Int): String = LocalContext.current.resources.getQuantityString(id, n, n)

fun formatDate(d: LocalDate?): String? =
    d?.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))

fun formatShortDate(d: LocalDate?): String? =
    d?.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

/** Scrollable page body with the app's side gutters. */
@Composable
fun ScreenColumn(bottomPadding: Int = 24, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = bottomPadding.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Arena.colors.ink,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
fun SaveAlert(save: SaveUi) {
    if (save.unavailable) {
        InlineAlert(text = stringResource(R.string.la_save_unavailable), tone = AlertTone.WARNING, modifier = Modifier.fillMaxWidth())
    } else if (save.error != null) {
        InlineAlert(text = save.error, tone = AlertTone.ERROR, modifier = Modifier.fillMaxWidth())
    }
}

/** Card row that opens another screen. */
@Composable
fun HubRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ArenaCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Arena.colors.brand, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Arena.colors.ink)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Arena.colors.inkSoft)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.la_chevron_cd),
                tint = Arena.colors.brand,
            )
        }
    }
}

/** [-] value [+] control with 48dp targets. */
@Composable
fun Stepper(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cd = stringResource(R.string.la_stepper_value_cd, label, value)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(value - 1) }, enabled = value > min, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.la_stepper_dec, label), tint = Arena.colors.brand)
        }
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = Arena.colors.ink,
            modifier = Modifier.widthIn48().semantics { contentDescription = cd },
        )
        IconButton(onClick = { onChange(value + 1) }, enabled = value < max, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.la_stepper_inc, label), tint = Arena.colors.brand)
        }
    }
}

private fun Modifier.widthIn48(): Modifier = this.then(Modifier.padding(horizontal = 8.dp))

@Composable
fun DateField(label: String, value: LocalDate?, error: String?, onClick: () -> Unit) {
    val shown = formatDate(value) ?: stringResource(R.string.la_date_unset)
    val cd = stringResource(R.string.la_date_cd, label, shown)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink, modifier = Modifier.semantics { heading() })
        SecondaryButton(
            text = shown,
            onClick = onClick,
            icon = Icons.Default.CalendarMonth,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { contentDescription = cd },
        )
        if (error != null) {
            Text(error, style = MaterialTheme.typography.labelLarge, color = Arena.colors.liveFg, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerHost(initial: LocalDate?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = dateToPickerMillis(initial ?: LocalDate.now()))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPick(pickerMillisToDate(it)) } ; onDismiss() },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.la_date_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.la_cancel)) }
        },
    ) { DatePicker(state = state) }
}

@Composable
fun formatLabel(f: LeagueFormat): String = stringResource(
    when (f) {
        LeagueFormat.ROUND_ROBIN -> R.string.la_format_round_robin
        LeagueFormat.SWISS -> R.string.la_format_swiss
        LeagueFormat.SINGLE_ELIMINATION -> R.string.la_format_single_elimination
        LeagueFormat.DOUBLE_ELIMINATION -> R.string.la_format_double_elimination
        LeagueFormat.HYBRID -> R.string.la_format_hybrid
    },
)

@Composable
fun tiebreakLabel(id: String): String = stringResource(
    when (id) {
        "head_to_head" -> R.string.la_tb_head_to_head
        "goal_difference" -> R.string.la_tb_goal_difference
        "goals_for" -> R.string.la_tb_goals_for
        "wins" -> R.string.la_tb_wins
        else -> R.string.la_tb_fair_play
    },
)

/** Runs [refresh] when the screen comes back to the front, but not on first show. */
@Composable
fun RefreshOnReturn(refresh: () -> Unit) {
    var first by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (first) first = false else refresh()
    }
}
