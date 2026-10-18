package com.nascon.league.ui.roster

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nascon.league.R
import com.nascon.league.core.error.ErrorContext
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing
import kotlinx.serialization.json.JsonObject

/** The four roster rule rejections that get a full explanation (mockups E11-E14). */
enum class RosterRejectionKind(
    val code: String,
    val icon: ImageVector,
    @StringRes val heading: Int,
    @StringRes val nextStep: Int,
) {
    WINDOW("TRANSFER_WINDOW_CLOSED", Icons.Default.EventBusy, R.string.rp_head_window, R.string.rp_next_window),
    ON_LOAN("PLAYER_ON_LOAN", Icons.Default.SyncAlt, R.string.rp_head_loan, R.string.rp_next_loan),
    POSITION("MIN_POSITION_VIOLATION", Icons.Default.PersonOff, R.string.rp_head_position, R.string.rp_next_position),
    SQUAD_FULL("SQUAD_FULL", Icons.Default.Groups, R.string.rp_head_full, R.string.rp_next_full);

    companion object {
        fun forCode(code: String?): RosterRejectionKind? = entries.firstOrNull { it.code == code }
    }
}

/** True when [RosterRejectionCard] has a dedicated presentation for this server code. */
fun hasRosterRejectionCard(code: String?): Boolean = RosterRejectionKind.forCode(code) != null

/** A rule rejection held by the roster flow state so the move form can show the card. */
data class RosterRejection(val code: String, val details: JsonObject? = null)

/**
 * Plain-language explanation of a rejected roster change: icon tile, heading, reason, next step.
 * [onBack] adds "Back to roster"; [onChangeDate] adds "Change date" (used for a closed transfer window).
 */
@Composable
fun RosterRejectionCard(
    code: String,
    details: JsonObject?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onChangeDate: (() -> Unit)? = null,
) {
    val kind = RosterRejectionKind.forCode(code)
    val c = Arena.colors
    val reason = ErrorMapper.forCode(code, ErrorContext.General, details)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = c.liveBg,
            border = BorderStroke(1.dp, c.liveFg.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        kind?.icon ?: Icons.Default.EventBusy,
                        contentDescription = null,
                        tint = c.liveFg,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    if (kind != null) {
                        Text(
                            stringResource(kind.heading),
                            style = MaterialTheme.typography.titleMedium,
                            color = c.liveFg,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    Text(reason, style = MaterialTheme.typography.bodyMedium, color = c.liveFg)
                }
            }
        }
        if (kind != null) {
            Text(stringResource(kind.nextStep), style = MaterialTheme.typography.bodyMedium, color = c.inkMid)
        }
        if (onChangeDate != null && kind == RosterRejectionKind.WINDOW) {
            PrimaryButton(stringResource(R.string.rp_change_date), onChangeDate, Modifier.fillMaxWidth())
        }
        if (onBack != null) {
            SecondaryButton(stringResource(R.string.rp_back_to_roster), onBack, Modifier.fillMaxWidth())
        }
    }
}
