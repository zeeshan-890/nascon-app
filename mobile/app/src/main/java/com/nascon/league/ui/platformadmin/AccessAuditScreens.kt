package com.nascon.league.ui.platformadmin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.platformadmin.ACCESS_ROLES
import com.nascon.league.data.platformadmin.AuditEntryDto
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ArenaTextField
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.ConfirmDialog
import com.nascon.league.ui.components.FilterPill
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.LoadingState
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.theme.Arena
import java.time.Instant

// ---------------------------------------------------------------- G12 access

@Composable
private fun ScopeCell(scope: String, editable: Boolean, onPick: (String) -> Unit) {
    val none = scope == SCOPE_NONE
    val text = if (none) stringResource(R.string.pa_scope_none) else scopeLabel(scope)
    val tone = when {
        none -> ChipTone.Error
        scope == "any" -> ChipTone.Success
        else -> ChipTone.Brand
    }
    val icon = if (none) Icons.Default.Block else Icons.Default.Check
    if (!editable) {
        StatusChip(text, icon, tone = tone)
        return
    }
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.DropdownList) { open = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(text, icon, tone = tone)
            Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.pa_change_access), tint = Arena.colors.brand)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SCOPE_OPTIONS.forEach { option ->
                val label = if (option == SCOPE_NONE) stringResource(R.string.pa_scope_none) else scopeLabel(option)
                DropdownMenuItem(
                    text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                    onClick = { open = false; onPick(option) },
                    trailingIcon = if (option == scope) {
                        { Icon(Icons.Default.Check, contentDescription = null, tint = Arena.colors.brand) }
                    } else null,
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

/** G12: who may do what. Real policy list shape from backend/src/domain/rbac/defaultPolicies.js. */
@Composable
fun AccessScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: AccessViewModel = viewModel(factory = AccessViewModel.factory(container))
    val s by vm.state.collectAsStateWithLifecycle()
    val lf = s.loadFailure

    if (s.rows.isEmpty()) {
        if (s.loading) LoadingState() else if (lf != null) PaFailure(lf, onRetry = vm::load, onBack = onBack)
        return
    }
    val sf = s.saveFailure
    if (sf?.unavailable == true) {
        PaFailure(sf, onRetry = null, onBack = { vm.discard() })
        return
    }
    val body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        if (sf != null) InlineAlert(sf.message, AlertTone.ERROR)
        if (s.savedOnce) InlineAlert(stringResource(R.string.pa_access_saved), AlertTone.SUCCESS)
        if (!s.editing) {
            PrimaryButton(
                text = stringResource(R.string.pa_edit_rules),
                onClick = vm::startEdit,
                icon = Icons.Default.Edit,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            InlineAlert(stringResource(R.string.pa_access_editing_hint), AlertTone.INFO)
        }
        s.rows.forEach { row ->
            ArenaCard(Modifier.fillMaxWidth()) {
                Text(actionLabel(row.action), style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink)
                ACCESS_ROLES.forEach { role ->
                    val editable = s.editing && canEditAccessCell(role)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(roleLabel(role), style = MaterialTheme.typography.bodyMedium, color = Arena.colors.inkSoft, modifier = Modifier.weight(1f))
                        if (s.editing && !canEditAccessCell(role)) {
                            Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.pa_locked), tint = Arena.colors.inkSoft)
                        }
                        ScopeCell(row.scopes[role] ?: SCOPE_NONE, editable) { vm.setScope(row.action, role, it) }
                    }
                }
            }
        }
        PaNote(stringResource(R.string.pa_access_note))
    }
    if (s.editing) {
        PaFormPage(
            bottomBar = {
                PaButtonPair(
                    secondaryText = stringResource(R.string.pa_discard),
                    onSecondary = vm::discard,
                    primaryText = stringResource(R.string.pa_save_changes, s.changes),
                    onPrimary = { vm.askSave(true) },
                    primaryLoading = s.saving,
                    primaryEnabled = s.changes > 0,
                    secondaryEnabled = !s.saving,
                )
            },
            content = body,
        )
    } else {
        PaPage(content = body)
    }
    if (s.confirmSave) {
        ConfirmDialog(
            title = stringResource(R.string.pa_access_confirm_title),
            message = stringResource(R.string.pa_access_confirm_message, s.changes),
            confirmLabel = stringResource(R.string.pa_save_confirm),
            onConfirm = vm::save,
            onDismiss = { vm.askSave(false) },
            dismissLabel = stringResource(R.string.pa_cancel),
        )
    }
}

// ---------------------------------------------------------------- G13 audit list

@Composable
private fun auditMeta(e: AuditEntryDto, now: Instant): String {
    val yesterday = stringResource(R.string.pa_yesterday)
    val whenText = parseAuditInstant(e.at)?.let { formatAuditWhen(it, now, yesterdayLabel = yesterday) }.orEmpty()
    return listOf(e.actorName, e.target, whenText).filter { it.isNotBlank() }.joinToString(" · ")
}

/** G13: the audit log with quick category chips and a filter button. */
@Composable
fun AuditScreen(onEntry: (String) -> Unit, onFilter: () -> Unit, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: AuditListViewModel = viewModel(factory = AuditListViewModel.factory(container))
    val s by vm.state.collectAsStateWithLifecycle()
    val now = remember { Instant.now() }
    val failure = s.failure

    if (s.items.isEmpty() && s.loading) {
        LoadingState()
        return
    }
    if (s.items.isEmpty() && failure != null) {
        PaFailure(failure, onRetry = vm::reload, onBack = onBack)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill(stringResource(R.string.pa_cat_all), selected = s.filter.action == null, onClick = { vm.selectCategory(null) })
                AUDIT_CATEGORIES.forEach { c ->
                    FilterPill(categoryLabel(c), selected = s.filter.action == c, onClick = { vm.selectCategory(c) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(
                    text = stringResource(if (s.filter.isActive) R.string.pa_filter_on else R.string.pa_filter),
                    onClick = onFilter,
                    icon = Icons.Default.FilterList,
                )
            }
        }
        if (failure != null) item { InlineAlert(failure.message, AlertTone.WARNING) }
        if (s.items.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Default.FilterList, contentDescription = null, tint = Arena.colors.brand)
                    Text(
                        stringResource(R.string.pa_audit_empty_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Arena.colors.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    PaNote(stringResource(R.string.pa_audit_empty_message))
                    if (s.filter.isActive) {
                        SecondaryButton(stringResource(R.string.pa_reset), onClick = { AuditFilterHolder.reset() })
                    }
                }
            }
        }
        items(s.items, key = { it.id }) { e ->
            ArenaCard(Modifier.fillMaxWidth(), onClick = { onEntry(e.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(e.summary.ifBlank { actionLabel(e.action) }, style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink)
                        Text(auditMeta(e, now), style = MaterialTheme.typography.bodySmall, color = Arena.colors.inkSoft)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Arena.colors.brand)
                }
            }
        }
        if (s.hasMore) {
            item {
                SecondaryButton(
                    text = stringResource(R.string.pa_load_more),
                    onClick = vm::loadMore,
                    enabled = !s.loadingMore,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- G14 audit detail

@Composable
private fun DiffBlock(title: String, lines: List<Pair<String, String>>, bg: Color, fg: Color) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PaHeading(title)
        lines.forEach { (k, v) ->
            Text("${humanizeKey(k)}: ${v.ifBlank { stringResource(R.string.pa_empty_value) }}", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = fg)
        }
    }
}

/** G14: one audit entry with who / when / what changed. */
@Composable
fun AuditDetailScreen(entryId: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: AuditDetailViewModel = viewModel(key = "pa_audit_$entryId", factory = AuditDetailViewModel.factory(container, entryId))
    val s by vm.state.collectAsStateWithLifecycle()
    val e = s.entry
    HeaderSubtitle(e?.summary)
    if (e == null) {
        if (s.loading) LoadingState() else s.failure?.let { PaFailure(it, onRetry = vm::load, onBack = onBack) }
        return
    }
    val diff = diffJson(e.before, e.after)
    PaPage {
        PaInfoRow(stringResource(R.string.pa_by), listOf(e.actorName, if (e.actorRole.isBlank()) "" else roleLabel(e.actorRole)).filter { it.isNotBlank() }.joinToString(" · "))
        PaInfoRow(stringResource(R.string.pa_when), parseAuditInstant(e.at)?.let { formatAuditFull(it) }.orEmpty())
        if (e.target.isNotBlank()) PaInfoRow(stringResource(R.string.pa_target), e.target)
        if (e.requestId.isNotBlank()) PaInfoRow(stringResource(R.string.pa_request_id), e.requestId)
        PaHeading(stringResource(R.string.pa_what_changed))
        if (diff.isEmpty()) {
            PaNote(stringResource(R.string.pa_no_changes_recorded))
        } else {
            val colors = Arena.colors
            DiffBlock(
                stringResource(R.string.pa_before),
                diff.filter { it.before != null }.map { it.key to it.before.orEmpty() },
                colors.liveBg, colors.liveFg,
            )
            DiffBlock(
                stringResource(R.string.pa_after),
                diff.filter { it.after != null }.map { it.key to it.after.orEmpty() },
                colors.successBg, colors.successFg,
            )
        }
        e.reason?.takeIf { it.isNotBlank() }?.let { PaInfoRow(stringResource(R.string.pa_reason), it) }
    }
}

// ---------------------------------------------------------------- G15 audit filter (full screen route)

@Composable
fun AuditFilterScreen(onDone: () -> Unit, onCancel: () -> Unit) {
    val vm: AuditFilterViewModel = viewModel(factory = AuditFilterViewModel.factory())
    val s by vm.state.collectAsStateWithLifecycle()
    val actors by AuditFilterHolder.actors.collectAsStateWithLifecycle()
    val allActions = stringResource(R.string.pa_all_actions)
    val anyone = stringResource(R.string.pa_anyone)
    val actionOptions: List<Pair<String?, String>> =
        listOf<Pair<String?, String>>(null to allActions) + AUDIT_CATEGORIES.map { it to categoryLabel(it) }
    val actorOptions: List<Pair<String?, String>> = listOf<Pair<String?, String>>(null to anyone) + actors.map { it.first to it.second }
    val problem = s.problem

    PaFormPage(
        bottomBar = {
            PaButtonPair(
                secondaryText = stringResource(R.string.pa_reset),
                onSecondary = { vm.reset(); onDone() },
                primaryText = stringResource(R.string.pa_show_results),
                onPrimary = { if (vm.apply()) onDone() },
            )
        },
    ) {
        PaDropdownField(
            label = stringResource(R.string.pa_action_type),
            selectedText = actionOptions.firstOrNull { it.first == s.action }?.second ?: allActions,
            options = actionOptions,
            onSelect = vm::onAction,
        )
        PaDropdownField(
            label = stringResource(R.string.pa_done_by),
            selectedText = actorOptions.firstOrNull { it.first == s.actor }?.second ?: anyone,
            options = actorOptions,
            onSelect = vm::onActor,
        )
        ArenaTextField(
            value = s.from, onValueChange = vm::onFrom, label = stringResource(R.string.pa_from),
            placeholder = stringResource(R.string.pa_date_hint),
            errorText = if (problem == DateRangeProblem.FROM_INVALID) stringResource(R.string.pa_err_date) else null,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        ArenaTextField(
            value = s.to, onValueChange = vm::onTo, label = stringResource(R.string.pa_to),
            placeholder = stringResource(R.string.pa_date_hint),
            errorText = when (problem) {
                DateRangeProblem.TO_INVALID -> stringResource(R.string.pa_err_date)
                DateRangeProblem.FROM_AFTER_TO -> stringResource(R.string.pa_err_date_order)
                else -> null
            },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
    }
}
