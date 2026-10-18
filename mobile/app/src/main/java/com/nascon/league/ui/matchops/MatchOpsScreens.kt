@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nascon.league.ui.matchops

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.matchops.ConflictKind
import com.nascon.league.data.matchops.ConflictView
import com.nascon.league.data.matchops.MatchStatusMachine
import com.nascon.league.data.store.formatPkr
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.ConfirmDialog
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.FeatureUnavailableState
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.LoadingState
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.failure.userMessage
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat
import com.nascon.league.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Shared frame
// ---------------------------------------------------------------------------------------------

@Composable
private fun rememberOpsVm(): MatchOpsViewModel {
    val container = LocalAppContainer.current
    return viewModel(factory = MatchOpsViewModel.factory(container))
}

@Composable
private fun MatchOpsFrame(
    ui: MatchOpsUi,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    content: @Composable () -> Unit,
) {
    val subtitle = remember(ui.title, ui.venueName, ui.scheduledAt) {
        listOfNotNull(ui.title.takeIf { it.isNotBlank() }, MatchOpsFormat.whenText(ui.scheduledAt)).joinToString(" · ")
            .takeIf { it.isNotBlank() }
    }
    HeaderSubtitle(subtitle)
    val match = ui.match
    when {
        ui.unavailable -> FeatureUnavailableState(onBack = onBack)
        match == null && ui.refreshError != null -> {
            val err = checkNotNull(ui.refreshError)
            if (err is com.nascon.league.core.network.ApiError.Http && (err.status == 404 || err.code.endsWith("_NOT_FOUND"))) {
                EmptyState(
                    icon = Icons.Default.EventBusy,
                    title = stringResource(R.string.mo_gone_title),
                    message = stringResource(R.string.mo_gone_message),
                    actionLabel = stringResource(R.string.mo_back),
                    onAction = onBack,
                )
            } else {
                ErrorState(userMessage(err), onRetry)
            }
        }
        match == null -> LoadingState(stringResource(R.string.mo_loading))
        !ui.canManage -> EmptyState(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.mo_forbidden_title),
            message = stringResource(R.string.mo_forbidden_message),
            actionLabel = stringResource(R.string.mo_back),
            onAction = onBack,
        )
        else -> content()
    }
}

@Composable
private fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun OfflineNote() {
    InlineAlert(text = stringResource(R.string.mo_needs_internet), tone = AlertTone.WARNING, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
        color = Arena.colors.ink,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Arena.colors.inkSoft)
}

private fun statusLabelRes(status: String?): Int = when (status) {
    "upcoming" -> R.string.mo_status_upcoming
    "in_progress" -> R.string.mo_status_in_progress
    "completed" -> R.string.mo_status_completed
    "postponed" -> R.string.mo_status_postponed
    "abandoned" -> R.string.mo_status_abandoned
    "cancelled" -> R.string.mo_status_cancelled
    else -> R.string.mo_status_unknown
}

private fun statusIcon(status: String?): ImageVector = when (status) {
    "in_progress" -> Icons.Default.PlayArrow
    "completed" -> Icons.Default.CheckCircle
    "postponed" -> Icons.Default.Pause
    "abandoned" -> Icons.Default.Block
    "cancelled" -> Icons.Default.Cancel
    else -> Icons.Default.Schedule
}

private fun statusTone(status: String?): ChipTone = when (status) {
    "in_progress" -> ChipTone.Live
    "completed" -> ChipTone.Success
    "postponed" -> ChipTone.Warning
    "abandoned", "cancelled" -> ChipTone.Error
    else -> ChipTone.Brand
}

private fun actionLabelRes(status: String): Int = when (status) {
    "in_progress" -> R.string.mo_action_in_progress
    "postponed" -> R.string.mo_action_postponed
    "cancelled" -> R.string.mo_action_cancelled
    "completed" -> R.string.mo_action_completed
    "abandoned" -> R.string.mo_action_abandoned
    else -> R.string.mo_action_upcoming
}

/** Order of the status buttons, as in the mockup. */
private val ACTION_ORDER = listOf("in_progress", "postponed", "cancelled", "completed", "abandoned", "upcoming")

// ---------------------------------------------------------------------------------------------
// F19 Match admin (+ F20 confirm dialog)
// ---------------------------------------------------------------------------------------------

@Composable
fun MatchAdminScreen(
    onBack: () -> Unit,
    onReschedule: (String) -> Unit,
    onPins: (String) -> Unit,
) {
    val vm = rememberOpsVm()
    val ui by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val changed = stringResource(R.string.mo_status_changed)
    LaunchedEffect(vm) {
        vm.eventFlow.collect { e ->
            when (e) {
                is MatchOpsEvent.StatusChanged -> snackbar.showSnackbar(changed)
                is MatchOpsEvent.Failed -> snackbar.showSnackbar(userMessage(e.error))
                else -> Unit
            }
        }
    }
    MatchOpsFrame(ui, onBack, vm::retry) {
        MatchAdminContent(
            ui = ui,
            onAsk = vm::askStatus,
            onReschedule = { onReschedule(vm.matchId) },
            onPins = { onPins(vm.matchId) },
        )
        ui.confirmTo?.let { to ->
            val copy = confirmCopy(to)
            ConfirmDialog(
                title = stringResource(copy.title),
                message = stringResource(copy.message),
                confirmLabel = stringResource(copy.button),
                onConfirm = { if (!ui.offline) vm.confirmStatus() },
                onDismiss = vm::dismissConfirm,
                destructive = copy.destructive,
                dismissLabel = stringResource(R.string.mo_keep),
            )
        }
    }
}

private data class ConfirmCopy(val title: Int, val message: Int, val button: Int, val destructive: Boolean)

private fun confirmCopy(to: String): ConfirmCopy = when (to) {
    "in_progress" -> ConfirmCopy(R.string.mo_confirm_in_progress_title, R.string.mo_confirm_in_progress_msg, R.string.mo_confirm_in_progress_btn, false)
    "completed" -> ConfirmCopy(R.string.mo_confirm_completed_title, R.string.mo_confirm_completed_msg, R.string.mo_confirm_completed_btn, false)
    "postponed" -> ConfirmCopy(R.string.mo_confirm_postponed_title, R.string.mo_confirm_postponed_msg, R.string.mo_confirm_postponed_btn, true)
    "abandoned" -> ConfirmCopy(R.string.mo_confirm_abandoned_title, R.string.mo_confirm_abandoned_msg, R.string.mo_confirm_abandoned_btn, true)
    "cancelled" -> ConfirmCopy(R.string.mo_confirm_cancelled_title, R.string.mo_confirm_cancelled_msg, R.string.mo_confirm_cancelled_btn, true)
    else -> ConfirmCopy(R.string.mo_confirm_upcoming_title, R.string.mo_confirm_upcoming_msg, R.string.mo_confirm_upcoming_btn, false)
}

@Composable
private fun MatchAdminContent(
    ui: MatchOpsUi,
    onAsk: (String) -> Unit,
    onReschedule: () -> Unit,
    onPins: () -> Unit,
) {
    val status = ui.status
    val allowed = status?.let { MatchStatusMachine.nextStatuses(it) }.orEmpty()
    val final = status != null && MatchStatusMachine.isFinal(status)
    ScreenColumn {
        StatusChip(
            text = stringResource(R.string.mo_status_current, stringResource(statusLabelRes(status))),
            icon = statusIcon(status),
            tone = statusTone(status),
        )
        if (ui.offline) OfflineNote()
        SectionTitle(stringResource(R.string.mo_change_status))
        ACTION_ORDER.forEach { target ->
            val isAllowed = target in allowed
            // "Back to upcoming" only exists as a move out of postponed; hide it when it does not apply.
            if (target == "upcoming" && !isAllowed) return@forEach
            val hint = when {
                isAllowed -> null
                final -> stringResource(R.string.mo_match_final)
                target in MatchStatusMachine.NEEDS_LIVE -> stringResource(R.string.mo_needs_live)
                else -> stringResource(R.string.mo_not_now)
            }
            ActionRow(
                label = stringResource(actionLabelRes(target)),
                hint = hint,
                enabled = isAllowed && !ui.busy && !ui.offline,
                canNavigate = isAllowed,
                destructive = target == "cancelled" || target == "abandoned",
                onClick = { onAsk(target) },
            )
        }
        ActionRow(
            label = stringResource(R.string.mo_reschedule),
            hint = if (final) stringResource(R.string.mo_match_final) else null,
            enabled = !final,
            canNavigate = !final,
            onClick = onReschedule,
        )
        ActionRow(
            label = stringResource(R.string.mo_pins_row),
            hint = null,
            enabled = true,
            canNavigate = true,
            icon = Icons.Default.ShoppingBag,
            onClick = onPins,
        )
        Note(stringResource(R.string.mo_rules_note))
    }
}

@Composable
private fun ActionRow(
    label: String,
    hint: String?,
    enabled: Boolean,
    canNavigate: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    val colors = Arena.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (canNavigate && enabled) 1f else 0.6f)
            .clip(shape)
            .background(Color.White)
            .border(BorderStroke(1.5.dp, colors.cardBorder), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 54.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = colors.brand)
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (destructive && enabled) colors.liveFg else colors.ink,
            fontFamily = Montserrat,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        if (hint != null) {
            Text(hint, color = colors.inkSoft, fontFamily = Montserrat, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        } else if (canNavigate) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.inkSoft)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// F21 Reschedule + F22 Conflict
// ---------------------------------------------------------------------------------------------

@Composable
fun MatchRescheduleScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val vm = rememberOpsVm()
    val ui by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val saved = stringResource(R.string.mo_schedule_saved)
    LaunchedEffect(vm) {
        vm.eventFlow.collect { e ->
            when (e) {
                MatchOpsEvent.ScheduleSaved -> {
                    snackbar.showSnackbar(saved)
                    onDone()
                }
                is MatchOpsEvent.Failed -> snackbar.showSnackbar(userMessage(e.error))
                else -> Unit
            }
        }
    }
    LaunchedEffect(ui.match != null, ui.scheduledAt) { if (ui.match != null) vm.ensureScheduleDefaults() }
    val conflicts = ui.conflicts
    BackHandler(enabled = conflicts != null) { vm.backToForm() }
    MatchOpsFrame(ui, onBack, vm::retry) {
        if (conflicts != null) {
            ConflictContent(ui, conflicts, onPickAnother = vm::backToForm, onCancel = onBack)
        } else {
            RescheduleForm(
                ui = ui,
                onDate = vm::setDate,
                onTime = vm::setTime,
                onSubmit = vm::submitReschedule,
                onCancel = onBack,
            )
        }
    }
}

@Composable
private fun RescheduleForm(
    ui: MatchOpsUi,
    onDate: (LocalDate) -> Unit,
    onTime: (Int, Int) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    val date = ui.dateEpochDay?.let { LocalDate.ofEpochDay(it) }
    val minute = ui.minuteOfDay
    val locale = Locale.getDefault()
    val now = MatchOpsFormat.whenText(ui.scheduledAt)
    ScreenColumn {
        if (ui.offline) OfflineNote()
        ArenaCard(Modifier.fillMaxWidth()) {
            Text(
                ui.title.ifBlank { stringResource(R.string.mo_unknown_match) },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Arena.colors.ink,
            )
            val nowLine = when {
                now == null -> stringResource(R.string.mo_resched_no_time)
                ui.venueName != null -> stringResource(R.string.mo_resched_now_venue, now, ui.venueName)
                else -> stringResource(R.string.mo_resched_now, now)
            }
            Text(nowLine, style = MaterialTheme.typography.bodyMedium, color = Arena.colors.inkSoft)
        }
        val dateText = date?.let { MatchOpsFormat.date(it, locale) }.orEmpty()
        val timeText = minute?.let { MatchOpsFormat.time(it, locale) }.orEmpty()
        PickerField(stringResource(R.string.mo_new_date), dateText, Icons.Default.CalendarMonth) { showDate = true }
        PickerField(stringResource(R.string.mo_new_time), timeText, Icons.Default.Timer) { showTime = true }
        if (ui.venueName != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel(stringResource(R.string.mo_venue))
                Text(ui.venueName, style = MaterialTheme.typography.bodyLarge, color = Arena.colors.ink)
                Note(stringResource(R.string.mo_venue_note))
            }
        }
        Note(stringResource(R.string.mo_resched_note))
        Spacer(Modifier.size(4.dp))
        PrimaryButton(
            text = stringResource(R.string.mo_check_save),
            onClick = onSubmit,
            enabled = !ui.offline && !ui.busy && date != null && minute != null,
            loading = ui.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        SecondaryButton(
            text = stringResource(R.string.mo_cancel),
            onClick = onCancel,
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (showDate) {
        val initial = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val st = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    showDate = false
                }) { Text(stringResource(R.string.mo_ok)) }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text(stringResource(R.string.mo_cancel)) } },
        ) { DatePicker(state = st) }
    }
    if (showTime) {
        val st = rememberTimePickerState(
            initialHour = (minute ?: (18 * 60)) / 60,
            initialMinute = (minute ?: 0) % 60,
            is24Hour = false,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text(stringResource(R.string.mo_pick_time)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimePicker(state = st) } },
            confirmButton = {
                TextButton(onClick = {
                    onTime(st.hour, st.minute)
                    showTime = false
                }) { Text(stringResource(R.string.mo_ok)) }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text(stringResource(R.string.mo_cancel)) } },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Arena.colors.ink)
}

@Composable
private fun PickerField(label: String, value: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = Arena.colors
    val cd = stringResource(R.string.mo_field_value_cd, label, value)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(25.dp))
                .background(Color.White)
                .border(BorderStroke(2.dp, colors.fieldBorder), RoundedCornerShape(25.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 50.dp)
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = cd },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
            Icon(icon, contentDescription = null, tint = colors.brand)
        }
    }
}

@Composable
private fun ConflictContent(
    ui: MatchOpsUi,
    conflicts: List<ConflictView>,
    onPickAnother: () -> Unit,
    onCancel: () -> Unit,
) {
    ScreenColumn {
        InlineAlert(
            text = stringResource(R.string.mo_conflict_intro),
            tone = AlertTone.ERROR,
            modifier = Modifier.fillMaxWidth(),
        )
        conflicts.forEach { c -> ConflictCard(c, ui.venueName) }
        Spacer(Modifier.size(4.dp))
        PrimaryButton(
            text = stringResource(R.string.mo_pick_another),
            onClick = onPickAnother,
            modifier = Modifier.fillMaxWidth(),
        )
        SecondaryButton(text = stringResource(R.string.mo_cancel), onClick = onCancel, modifier = Modifier.fillMaxWidth())
    }
}

private fun conflictIcon(kind: ConflictKind): ImageVector = when (kind) {
    ConflictKind.VENUE_BOOKED, ConflictKind.VENUE_CLOSED -> Icons.Default.Place
    ConflictKind.TEAM_BUSY -> Icons.Default.Groups
    ConflictKind.PLAYER_BUSY -> Icons.Default.Person
    ConflictKind.NOT_ENOUGH_REST -> Icons.Default.Schedule
    ConflictKind.OTHER -> Icons.Default.EventBusy
}

@Composable
private fun ConflictCard(c: ConflictView, venueName: String?) {
    val colors = Arena.colors
    val teams = c.teams.takeIf { it.isNotEmpty() }?.let { MatchOpsFormat.joinNames(it) }
    val matches = c.matches.takeIf { it.isNotEmpty() }?.let { MatchOpsFormat.joinNames(it) }
    val title: String
    val message: String
    when (c.kind) {
        ConflictKind.VENUE_BOOKED -> {
            title = stringResource(R.string.mo_c_venue_title)
            val venue = venueName ?: stringResource(R.string.mo_the_venue)
            message = if (matches != null) stringResource(R.string.mo_c_venue_msg, venue, matches)
            else stringResource(R.string.mo_c_venue_msg_plain, venue)
        }
        ConflictKind.TEAM_BUSY -> {
            title = stringResource(R.string.mo_c_team_title)
            val who = teams ?: stringResource(R.string.mo_a_team)
            message = if (matches != null) stringResource(R.string.mo_c_team_msg, who, matches)
            else stringResource(R.string.mo_c_team_msg_plain, who)
        }
        ConflictKind.PLAYER_BUSY -> {
            title = stringResource(R.string.mo_c_player_title)
            message = if (c.playerCount > 0) pluralStringResource(R.plurals.mo_c_player_msg, c.playerCount, c.playerCount)
            else stringResource(R.string.mo_c_player_msg_plain)
        }
        ConflictKind.NOT_ENOUGH_REST -> {
            title = stringResource(R.string.mo_c_rest_title)
            message = if (teams != null) stringResource(R.string.mo_c_rest_msg, teams) else stringResource(R.string.mo_c_rest_msg_plain)
        }
        ConflictKind.VENUE_CLOSED -> {
            title = stringResource(R.string.mo_c_closed_title)
            message = stringResource(R.string.mo_c_closed_msg)
        }
        ConflictKind.OTHER -> {
            title = stringResource(R.string.mo_c_other_title)
            message = stringResource(R.string.mo_c_other_msg)
        }
    }
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White)
            .border(BorderStroke(1.dp, colors.liveFg.copy(alpha = 0.4f)), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(conflictIcon(c.kind), contentDescription = null, tint = colors.liveFg, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = colors.ink)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.inkSoft)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// E22 Pin product
// ---------------------------------------------------------------------------------------------

@Composable
fun MatchPinsScreen(onBack: () -> Unit) {
    val vm = rememberOpsVm()
    val ui by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val pinned = stringResource(R.string.mo_pinned_done)
    val unpinned = stringResource(R.string.mo_unpinned_done)
    LaunchedEffect(vm) {
        vm.eventFlow.collect { e ->
            when (e) {
                MatchOpsEvent.Pinned -> snackbar.showSnackbar(pinned)
                MatchOpsEvent.Unpinned -> snackbar.showSnackbar(unpinned)
                is MatchOpsEvent.Failed -> snackbar.showSnackbar(userMessage(e.error))
                else -> Unit
            }
        }
    }
    MatchOpsFrame(ui, onBack, vm::retry) {
        PinsContent(ui, onSelect = vm::select, onPin = vm::pinSelected, onUnpin = vm::unpin)
    }
}

@Composable
private fun PinsContent(
    ui: MatchOpsUi,
    onSelect: (String) -> Unit,
    onPin: () -> Unit,
    onUnpin: (String) -> Unit,
) {
    if (!ui.productsLoaded && ui.pinRows.isEmpty()) {
        LoadingState(stringResource(R.string.mo_loading))
        return
    }
    if (ui.pinRows.isEmpty()) {
        EmptyState(
            icon = Icons.Default.ShoppingBag,
            title = stringResource(R.string.mo_no_products_title),
            message = stringResource(R.string.mo_no_products_message),
        )
        return
    }
    ScreenColumn {
        if (ui.offline) OfflineNote()
        Note(stringResource(R.string.mo_pins_intro))
        ui.pinRows.forEach { row ->
            PinRowItem(
                row = row,
                selected = ui.selectedProduct == row.productId,
                enabled = !ui.busy,
                unpinEnabled = !ui.busy && !ui.offline,
                onSelect = { onSelect(row.productId) },
                onUnpin = { onUnpin(row.productId) },
            )
        }
        Spacer(Modifier.size(4.dp))
        PrimaryButton(
            text = stringResource(R.string.mo_pin_product),
            onClick = onPin,
            enabled = ui.selectedProduct != null && !ui.offline && !ui.busy,
            loading = ui.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PinRowItem(
    row: PinRow,
    selected: Boolean,
    enabled: Boolean,
    unpinEnabled: Boolean,
    onSelect: () -> Unit,
    onUnpin: () -> Unit,
) {
    val colors = Arena.colors
    val shape = RoundedCornerShape(20.dp)
    val name = row.name.ifBlank { stringResource(R.string.mo_product_unnamed) }
    val price = row.fromPriceMinor?.let { stringResource(R.string.mo_from_price, formatPkr(it)) }
    val selectedText = stringResource(if (selected) R.string.mo_row_selected else R.string.mo_row_not_selected, name)
    val rowModifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(Color.White)
        .border(BorderStroke(1.5.dp, if (selected) colors.brand else colors.cardBorder), shape)
    if (row.pinned) {
        Row(
            modifier = rowModifier.heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = colors.ink)
                if (price != null) Text(price, style = MaterialTheme.typography.bodyMedium, color = colors.inkSoft)
                StatusChip(stringResource(R.string.mo_pinned), Icons.Default.CheckCircle, tone = ChipTone.Success)
            }
            val unpinCd = stringResource(R.string.mo_unpin_cd, name)
            SecondaryButton(
                text = stringResource(R.string.mo_unpin),
                onClick = onUnpin,
                enabled = unpinEnabled,
                modifier = Modifier.semantics { contentDescription = unpinCd },
            )
        }
    } else {
        Row(
            modifier = rowModifier
                .clickable(enabled = enabled, role = Role.RadioButton, onClick = onSelect)
                .heightIn(min = 64.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) {
                    this.selected = selected
                    contentDescription = selectedText + (price?.let { ", $it" } ?: "")
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = colors.ink)
                if (price != null) Text(price, style = MaterialTheme.typography.bodyMedium, color = colors.inkSoft)
            }
            Spacer(Modifier.width(4.dp))
            RadioButton(selected = selected, onClick = null)
        }
    }
}
