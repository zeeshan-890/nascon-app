package com.nascon.league.ui.scoring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.match.asString
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.nascon.league.ui.components.ArenaTextField
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.theme.Arena
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascon.league.R
import com.nascon.league.ui.components.ConfirmDialog
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Spacing

/** Hosts the snackbar messages and confirmation dialogs for a [ScoringViewModel]. Place once per screen. */
@Composable
fun ScoringHost(vm: ScoringViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val ctx = LocalContext.current
    LaunchedEffect(vm) {
        vm.messageFlow.collect { msg ->
            val res = when (msg) {
                ScoringMessage.SCORE_ADDED -> R.string.f8_msg_score_added
                ScoringMessage.SCORE_SAVED_OFFLINE -> R.string.f8_msg_score_offline
                ScoringMessage.CHANGE_SAVED -> R.string.f8_msg_change_saved
                ScoringMessage.CHANGE_SAVED_OFFLINE -> R.string.f8_msg_change_offline
                ScoringMessage.FAILED -> R.string.f8_msg_failed
            }
            snackbar.showSnackbar(ctx.getString(res))
        }
    }

    val home = s.homeName ?: stringResource(R.string.f3_home_team)
    val away = s.awayName ?: stringResource(R.string.f3_away_team)
    if (s.showEndConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.scoringplus_end_title),
            message = stringResource(R.string.scoringplus_end_message, home, s.homeScore, s.awayScore, away),
            confirmLabel = stringResource(R.string.scoringplus_end_confirm),
            onConfirm = vm::confirmEnd,
            onDismiss = vm::dismissEnd,
            destructive = false,
            dismissLabel = stringResource(R.string.scoringplus_end_keep),
        )
    }
    val m = s.match
    val teams = if (m == null) emptyList() else listOf(TeamChoice(m.home.teamId, home), TeamChoice(m.away.teamId, away))
    if (s.showTieBlocked) {
        AlertDialog(
            onDismissRequest = vm::dismissTieBlocked,
            shape = RoundedCornerShape(24.dp),
            containerColor = Color.White,
            icon = {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Arena.colors.liveFg, modifier = Modifier.size(32.dp))
            },
            title = {
                Text(
                    ErrorMapper.forCode("TIE_NOT_ALLOWED"),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = Arena.colors.liveFg,
                    modifier = Modifier.semantics { heading() },
                )
            },
            text = {
                Text(
                    stringResource(R.string.scoringplus_tie_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Arena.colors.ink,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                PrimaryButton(
                    text = stringResource(R.string.scoringplus_tie_record),
                    onClick = vm::openTiebreakFromBlocked,
                    modifier = Modifier.heightIn(min = 50.dp),
                )
            },
            dismissButton = {
                SecondaryButton(
                    text = stringResource(R.string.scoringplus_tie_keep_playing),
                    onClick = vm::dismissTieBlocked,
                    modifier = Modifier.heightIn(min = 50.dp),
                )
            },
        )
    }
    if (s.showTiebreak) {
        TiebreakSheet(
            teams = teams,
            homeScore = s.homeScore,
            awayScore = s.awayScore,
            onConfirm = { vm.confirmTiebreak(it.teamId, it.name) },
            onDismiss = vm::dismissTiebreak,
        )
    }
    if (s.showDiscipline) {
        DisciplineSheet(
            teams = teams,
            options = s.disciplineOptions,
            onConfirm = { t, pts -> vm.confirmDiscipline(t.teamId, t.name, pts) },
            onDismiss = vm::dismissDiscipline,
        )
    }
    val undoId = s.undoEventId
    if (undoId != null) {
        val described = s.timeline.firstOrNull { it.key == "e-$undoId" }?.let { item ->
            listOfNotNull(item.minute?.let { m -> m + "′" }, item.teamName, item.label.asString()).joinToString(" · ")
        }
        UndoDialog(
            eventId = undoId,
            description = described,
            error = s.reasonError,
            onReasonChange = vm::onReasonChange,
            onConfirm = vm::confirmUndo,
            onDismiss = vm::dismissUndo,
        )
    }
}

@Composable
fun UndoDialog(
    eventId: String,
    description: String? = null,
    error: ReasonError?,
    onReasonChange: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by rememberSaveable(eventId) { mutableStateOf("") }
    val a = Arena.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color.White,
        title = {
            Text(
                stringResource(R.string.scoringplus_undo_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = a.ink,
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (!description.isNullOrBlank()) {
                    Text(description, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = a.ink)
                }
                Text(stringResource(R.string.scoringplus_undo_message), style = MaterialTheme.typography.bodyMedium, color = a.inkMid)
                ArenaTextField(
                    value = reason,
                    onValueChange = {
                        reason = it
                        onReasonChange(it)
                    },
                    label = stringResource(R.string.scoringplus_reason_label),
                    isError = error != null,
                    errorText = when (error) {
                        ReasonError.EMPTY -> stringResource(R.string.f8_reason_empty)
                        ReasonError.TOO_LONG -> stringResource(R.string.f8_reason_too_long)
                        null -> null
                    },
                    supportingText = if (error == null) stringResource(R.string.f8_reason_counter, reason.length) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reason) },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = a.liveFg, contentColor = Color.White),
                modifier = Modifier.heightIn(min = 50.dp),
            ) {
                Text(stringResource(R.string.scoringplus_undo_confirm), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            SecondaryButton(
                text = stringResource(R.string.scoringplus_undo_keep),
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 50.dp),
            )
        },
    )
}
