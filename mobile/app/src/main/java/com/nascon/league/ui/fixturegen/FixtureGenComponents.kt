package com.nascon.league.ui.fixturegen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nascon.league.R
import com.nascon.league.ui.components.ArenaTextField
import com.nascon.league.ui.components.FilterPill
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Page frame: one vertical scroller for the content and a fixed action row at the bottom. */
@Composable
fun GenScaffold(
    bottom: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Arena.colors.bg)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = bottom,
        )
    }
}

@Composable
fun GenLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Arena.colors.ink,
        fontFamily = Montserrat,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
fun GenBody(text: String, modifier: Modifier = Modifier, color: Color = Arena.colors.inkSoft) {
    Text(text, color = color, fontFamily = Montserrat, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, modifier = modifier)
}

/** Rounded white row with an optional trailing switch (F10 "Double round robin", F13 "Grand final reset"). */
@Composable
fun GenSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Arena.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White)
            .border(1.dp, c.cardBorder, shape)
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            GenBody(subtitle)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** "Teams · 8 selected · Edit" row. */
@Composable
fun GenTeamsRow(count: Int, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val c = Arena.colors
    val shape = RoundedCornerShape(24.dp)
    val label = stringResource(R.string.fg_teams)
    val summary = pluralStringResource(R.plurals.fg_teams_selected, count, count)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White)
            .border(2.dp, c.fieldBorder, shape)
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.fg_teams_edit), onClick = onEdit)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = c.ink, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(summary, color = c.inkSoft, fontFamily = Montserrat, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.fg_teams_edit), color = c.brand, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.brand)
    }
}

/** Dialog with a checkbox per available team. */
@Composable
fun TeamPickerDialog(
    teams: List<TeamOpt>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onAll: () -> Unit,
    onNone: () -> Unit,
    onDone: () -> Unit,
) {
    val c = Arena.colors
    AlertDialog(
        onDismissRequest = onDone,
        title = {
            Text(
                stringResource(R.string.fg_pick_teams_title),
                fontFamily = Montserrat, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = c.ink,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onAll, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.fg_select_all)) }
                    TextButton(onClick = onNone, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.fg_select_none)) }
                }
                teams.forEach { t ->
                    val on = t.id in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle(t.id) }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Text(t.name, fontFamily = Montserrat, fontSize = 14.sp, color = c.ink)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.fg_done), fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
fun GenStepper(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    valueText: String = value.toString(),
) {
    val c = Arena.colors
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White)
            .border(2.dp, c.fieldBorder, shape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label, color = c.ink, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        IconButton(onClick = { onChange(value - 1) }, enabled = value > range.first, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.fg_decrease, label))
        }
        Text(
            valueText, color = c.ink, fontFamily = Montserrat, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.widthIn(min = 28.dp).semantics { contentDescription = "$label: $valueText" },
        )
        IconButton(onClick = { onChange(value + 1) }, enabled = value < range.last, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.fg_increase, label))
        }
    }
}

/** Start date field plus match-day chips; shared by every options screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenScheduleSection(
    startDate: String,
    matchDays: Set<Int>,
    onStartDate: (String) -> Unit,
    onToggleDay: (Int) -> Unit,
) {
    val parsed = GenSchedule.parseDate(startDate)
    ArenaTextField(
        value = startDate,
        onValueChange = onStartDate,
        label = stringResource(R.string.fg_start_date),
        placeholder = stringResource(R.string.fg_date_hint),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        errorText = if (startDate.isNotBlank() && parsed == null) stringResource(R.string.fg_err_date) else null,
        supportingText = parsed?.let { GenSchedule.format(it) },
    )
    GenLabel(stringResource(R.string.fg_match_days))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (d in 1..7) {
            val name = DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, Locale.getDefault())
            FilterPill(text = name, selected = d in matchDays, onClick = { onToggleDay(d) })
        }
    }
    if (matchDays.isEmpty()) GenBody(stringResource(R.string.fg_err_matchdays), color = Arena.colors.liveFg)
}

/** Seed order list with up/down buttons (accessible alternative to drag and drop). */
@Composable
fun GenSeedList(
    selected: List<String>,
    names: Map<String, String>,
    onMove: (String, Int) -> Unit,
    knockout: Boolean,
) {
    val c = Arena.colors
    val n = selected.size
    selected.forEachIndexed { i, id ->
        val seed = i + 1
        val name = teamLabel(id, names)
        val opp = firstRoundOpponent(seed, n)
        val note = when {
            !knockout -> null
            opp == null -> stringResource(R.string.fg_bye)
            else -> stringResource(R.string.fg_vs_seed, opp)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .border(BorderStroke(1.dp, c.cardBorder), RoundedCornerShape(14.dp))
                .padding(start = 14.dp, top = 2.dp, bottom = 2.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "$seed · $name", color = c.ink, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (note != null) {
                Text(note, color = c.inkSoft, fontFamily = Montserrat, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = { onMove(id, -1) }, enabled = i > 0, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.fg_move_up, name))
            }
            IconButton(onClick = { onMove(id, 1) }, enabled = i < n - 1, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.fg_move_down, name))
            }
        }
    }
}

/** Radio-style choice card used by the format picker. */
@Composable
fun GenChoiceCard(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Arena.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) c.chipBg else Color.White)
            .border(if (selected) 2.dp else 1.5.dp, if (selected) c.brand else c.cardBorder, shape)
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontFamily = Montserrat, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            GenBody(description)
        }
    }
}

@Composable
fun GenCentered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Arena.colors.bg), contentAlignment = Alignment.Center) { content() }
}
