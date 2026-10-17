package com.nascon.league.ui.fixtures

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nascon.league.R
import com.nascon.league.domain.model.League
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing

/** "Choose league" bottom sheet. The caller owns visibility (use rememberSaveable so it survives rotation). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaguePickerSheet(
    leagues: List<League>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val a = Arena.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selectedCd = stringResource(R.string.ms_league_selected_cd)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = androidx.compose.ui.graphics.Color.White) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.md),
        ) {
            Text(
                stringResource(R.string.ms_league_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = a.ink,
                modifier = Modifier.padding(bottom = Spacing.xs).semantics { heading() },
            )
            leagues.forEachIndexed { i, l ->
                val sel = l.leagueId == selectedId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(selected = sel, role = Role.RadioButton, onClick = {
                            onSelect(l.leagueId)
                            onDismiss()
                        })
                        .padding(vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        l.name,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.Bold,
                        ),
                        color = a.ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (sel) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = selectedCd,
                            tint = a.brand,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                if (i < leagues.lastIndex) HorizontalDivider(color = a.cardBorder)
            }
        }
    }
}
