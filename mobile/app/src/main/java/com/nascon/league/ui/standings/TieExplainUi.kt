package com.nascon.league.ui.standings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nascon.league.R
import com.nascon.league.data.api.dto.StandingsDto
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing

/** B13 "Why this order": numbered tie-break steps, the deciding one marked "Decided". */
@Composable
internal fun TieExplainBlock(standings: StandingsDto, ex: TieExplanation) {
    val names = standings.teamNames
    val me = teamDisplayName(names, ex.teamId)
    val other = teamDisplayName(names, ex.otherId)
    val rows = standings.table
    val mine = rows.firstOrNull { it.teamId == ex.teamId }
    val theirs = rows.firstOrNull { it.teamId == ex.otherId }
    val h2hMine = parseH2h(standings.h2h, ex.teamId, listOf(ex.otherId)).firstOrNull()
    val h2hTheirs = parseH2h(standings.h2h, ex.otherId, listOf(ex.teamId)).firstOrNull()
    val steps = visibleTieSteps(ex.decidedBy)
    val whyText = stringResource(if (ex.above) R.string.cs_why_above else R.string.cs_why_below, me, other)

    Column(Modifier.fillMaxWidth()) {
        Text(
            whyText,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.cs_tied_points, ex.points),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm),
        )
        steps.forEachIndexed { i, step ->
            val label = stringResource(
                when (step) {
                    TieStep.HEAD_TO_HEAD -> R.string.cs_label_h2h
                    TieStep.GOAL_DIFF -> R.string.cs_label_diff
                    TieStep.SCORED -> R.string.cs_label_scored
                    TieStep.FAIR_PLAY -> R.string.cs_label_fair
                    TieStep.LOTS -> R.string.cs_label_lots
                },
            )
            val needed = step.ordinal <= ex.decidedBy.ordinal
            val decided = step == ex.decidedBy
            val text = if (!needed) {
                stringResource(R.string.cs_step_not_needed, label)
            } else when (step) {
                TieStep.HEAD_TO_HEAD -> stringResource(
                    R.string.cs_step_h2h, me, h2hMine?.points ?: 0, other, h2hTheirs?.points ?: 0,
                )
                TieStep.GOAL_DIFF -> stringResource(
                    R.string.cs_step_diff, me, formatDiff(mine?.diff ?: 0.0), other, formatDiff(theirs?.diff ?: 0.0),
                )
                TieStep.SCORED -> stringResource(
                    R.string.cs_step_scored, me, mine?.scored ?: 0, other, theirs?.scored ?: 0,
                )
                TieStep.FAIR_PLAY -> stringResource(
                    R.string.cs_step_fair, me, mine?.fairPlay ?: 0, other, theirs?.fairPlay ?: 0,
                )
                TieStep.LOTS -> stringResource(R.string.cs_step_lots)
            }
            val decidedText = stringResource(R.string.cs_decided)
            val cd = stringResource(R.string.cs_step_cd, i + 1, if (decided) "$text. $decidedText" else text)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(vertical = Spacing.sm)
                    .semantics(mergeDescendants = true) { contentDescription = cd },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(Arena.colors.chipBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (i + 1).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Arena.colors.brand,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (needed) Arena.colors.ink else Arena.colors.inkSoft,
                    )
                    if (decided) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Arena.colors.successFg,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                decidedText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Arena.colors.successFg,
                                modifier = Modifier.padding(start = Spacing.xs),
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = Arena.colors.cardBorder)
        }
    }
}
