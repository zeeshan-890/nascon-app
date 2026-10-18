package com.nascon.league.ui.scoring

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.theme.Arena
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascon.league.R
import com.nascon.league.data.api.dto.MatchDetailDto
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.theme.Spacing

private val ButtonMinHeight = 56.dp

/**
 * Scoring controls. Renders nothing unless the user may score and the match status allows START/SCORE controls.
 * Hosting screens must also place [ScoringHost] once for dialogs and snackbars.
 */
@Composable
fun ScoringPanel(vm: ScoringViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()
    ScoringPanelContent(s, vm, modifier)
}

@Composable
internal fun ScoringPanelContent(s: ScoringUiState, vm: ScoringViewModel, modifier: Modifier = Modifier) {
    val m = s.match ?: return
    when (surfaceFor(s.showPanel, s.mode, embedded = true)) {
        ScoringSurface.START -> Column(modifier.fillMaxWidth().padding(horizontal = Spacing.md)) {
            PrimaryButton(
                text = stringResource(R.string.f8_start_match),
                onClick = vm::onStart,
                icon = Icons.Default.PlayArrow,
                modifier = Modifier.fillMaxWidth().heightIn(min = ButtonMinHeight),
            )
        }
        ScoringSurface.SCORE -> ScoreControls(s, m, vm, modifier)
        else -> Unit
    }
}

@Composable
private fun ScoreControls(s: ScoringUiState, m: MatchDetailDto, vm: ScoringViewModel, modifier: Modifier) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val homeName = s.homeName ?: stringResource(R.string.f3_home_team)
    val awayName = s.awayName ?: stringResource(R.string.f3_away_team)
    ArenaCard(modifier.fillMaxWidth().padding(horizontal = Spacing.md)) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                stringResource(R.string.f8_panel_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = Arena.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            if (s.buttons.isEmpty() && !s.supportsDiscipline) {
                Text(stringResource(R.string.f8_no_buttons), style = MaterialTheme.typography.bodyLarge)
            }
            if (landscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    TeamControls(s, m.home.teamId, homeName, vm, Modifier.weight(1f))
                    TeamControls(s, m.away.teamId, awayName, vm, Modifier.weight(1f))
                }
            } else {
                TeamControls(s, m.home.teamId, homeName, vm, Modifier.fillMaxWidth())
                TeamControls(s, m.away.teamId, awayName, vm, Modifier.fillMaxWidth())
            }
            if (s.supportsDiscipline) {
                val cd = stringResource(R.string.scoringplus_discipline_button_cd)
                SecondaryButton(
                    text = stringResource(R.string.scoringplus_discipline_button),
                    onClick = vm::openDiscipline,
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonMinHeight).semantics { contentDescription = cd },
                )
            }
            if (s.tiebreakAvailable) {
                val cd = stringResource(R.string.scoringplus_tiebreak_button_cd)
                SecondaryButton(
                    text = stringResource(R.string.scoringplus_tiebreak_button),
                    onClick = vm::openTiebreak,
                    icon = Icons.Default.EmojiEvents,
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonMinHeight).semantics { contentDescription = cd },
                )
            }
            SecondaryButton(
                text = stringResource(R.string.f8_end_match),
                onClick = vm::requestEnd,
                icon = Icons.Default.Stop,
                destructive = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = ButtonMinHeight),
            )
        }
    }
}

@Composable
private fun TeamControls(
    s: ScoringUiState,
    teamId: String,
    teamName: String,
    vm: ScoringViewModel,
    modifier: Modifier = Modifier,
) {
    val sectionCd = stringResource(R.string.f8_team_section_cd, teamName)
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Arena.colors.surfaceAlt)
            .padding(Spacing.md)
            .semantics { contentDescription = sectionCd },
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            teamName,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = Arena.colors.brandDeep,
        )
        s.buttons.forEach { b ->
            val text = if (b.points != null) {
                stringResource(R.string.f8_score_button_points, b.label, b.points)
            } else {
                stringResource(R.string.f8_score_button, b.label)
            }
            val cd = stringResource(R.string.f8_score_button_cd, b.label, teamName)
            PrimaryButton(
                text = text,
                onClick = { vm.onScore(teamId, teamName, b) },
                icon = Icons.Default.Add,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ButtonMinHeight)
                    .semantics { contentDescription = cd },
            )
        }
    }
}
