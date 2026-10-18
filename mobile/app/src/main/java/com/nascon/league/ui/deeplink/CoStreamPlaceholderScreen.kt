package com.nascon.league.ui.deeplink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nascon.league.R
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat
import com.nascon.league.ui.theme.Spacing

/** Temporary shell; a later phase replaces it with the real Co-Stream viewer. */
@Composable
fun CoStreamPlaceholderScreen(sessionId: String, onGoHome: () -> Unit) {
    val c = Arena.colors
    val homeDesc = stringResource(R.string.dl_go_home_desc)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("costream_root")
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            stringResource(R.string.dl_costream_title),
            color = c.ink,
            fontFamily = Montserrat,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.dl_costream_session, sessionId),
            color = c.inkSoft,
            fontFamily = Montserrat,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        StatusChip(
            text = stringResource(R.string.dl_costream_connecting),
            icon = Icons.Default.Sync,
            tone = ChipTone.Brand,
        )
        Text(
            stringResource(R.string.dl_costream_ready),
            color = c.inkSoft,
            fontFamily = Montserrat,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        PrimaryButton(
            text = stringResource(R.string.dl_go_home),
            onClick = onGoHome,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("costream_home")
                .semantics { contentDescription = homeDesc },
        )
    }
}
