package com.nascon.league.ui.scoring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nascon.league.R
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing

/** One team the user can pick in a sheet. */
data class TeamChoice(val teamId: String, val name: String)

@Composable
private fun TeamRadioRow(name: String, selected: Boolean, onSelect: () -> Unit) {
    val a = Arena.colors
    val selectedCd = stringResource(R.string.scoringplus_selected_cd)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(if (selected) 2.dp else 1.5.dp, if (selected) a.brand else a.cardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        Row(
            Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (selected) selectedCd else null,
                tint = a.brand,
                modifier = Modifier.size(24.dp),
            )
            Text(
                name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = a.ink,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SheetButtons(
    confirmText: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
        PrimaryButton(
            text = confirmText,
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        )
        SecondaryButton(
            text = stringResource(R.string.scoringplus_cancel),
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        )
    }
}

/** F25: choose which team won the tie-break. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TiebreakSheet(
    teams: List<TeamChoice>,
    homeScore: Int,
    awayScore: Int,
    onConfirm: (TeamChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val a = Arena.colors
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Color.White) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                stringResource(R.string.scoringplus_tiebreak_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = a.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.scoringplus_tiebreak_message, homeScore, awayScore),
                style = MaterialTheme.typography.bodyMedium,
                color = a.inkMid,
            )
            teams.forEach { t -> TeamRadioRow(t.name, picked == t.teamId) { picked = t.teamId } }
            SheetButtons(
                confirmText = stringResource(R.string.scoringplus_tiebreak_record),
                confirmEnabled = picked != null,
                onConfirm = { teams.firstOrNull { it.teamId == picked }?.let(onConfirm) },
                onCancel = onDismiss,
            )
        }
    }
}

/** F26: record a discipline event for a team. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisciplineSheet(
    teams: List<TeamChoice>,
    options: List<Int>,
    onConfirm: (TeamChoice, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val a = Arena.colors
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var points by rememberSaveable { mutableIntStateOf(ScoringLogic.initialDiscipline(options)) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Color.White) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                stringResource(R.string.scoringplus_discipline_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = a.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.scoringplus_team_label),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = a.ink,
            )
            teams.forEach { t -> TeamRadioRow(t.name, picked == t.teamId) { picked = t.teamId } }
            Text(
                stringResource(R.string.scoringplus_points_label),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = a.ink,
            )
            val valueCd = stringResource(R.string.scoringplus_points_value_cd, points)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepperButton(Icons.Default.Remove, stringResource(R.string.scoringplus_points_less_cd)) {
                    points = ScoringLogic.stepDiscipline(options, points, -1)
                }
                Text(
                    points.toString(),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = a.ink,
                    modifier = Modifier.semantics { contentDescription = valueCd },
                )
                StepperButton(Icons.Default.Add, stringResource(R.string.scoringplus_points_more_cd)) {
                    points = ScoringLogic.stepDiscipline(options, points, 1)
                }
            }
            Text(
                stringResource(R.string.scoringplus_discipline_hint),
                style = MaterialTheme.typography.bodySmall,
                color = a.inkMid,
            )
            SheetButtons(
                confirmText = stringResource(R.string.scoringplus_record),
                confirmEnabled = picked != null,
                onConfirm = { teams.firstOrNull { it.teamId == picked }?.let { onConfirm(it, points) } },
                onCancel = onDismiss,
            )
        }
    }
}

@Composable
private fun StepperButton(icon: ImageVector, cd: String, onClick: () -> Unit) {
    val a = Arena.colors
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.White,
        border = BorderStroke(2.dp, a.brand),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
            Icon(icon, contentDescription = cd, tint = a.brand)
        }
    }
}
