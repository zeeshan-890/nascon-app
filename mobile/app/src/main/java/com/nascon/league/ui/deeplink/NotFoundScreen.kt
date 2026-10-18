package com.nascon.league.ui.deeplink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
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
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat
import com.nascon.league.ui.theme.Spacing

@Composable
fun NotFoundScreen(
    reason: String?,
    onRetry: (() -> Unit)?,
    onGoHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Arena.colors
    val content = NotFoundCopy.contentFor(reason)
    val retryDesc = stringResource(R.string.dl_retry_desc)
    val homeDesc = stringResource(R.string.dl_go_home_desc)
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("not_found_root")
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(
            Icons.Default.SearchOff,
            contentDescription = stringResource(R.string.dl_not_found_icon_desc),
            tint = c.brand,
            modifier = Modifier.size(72.dp),
        )
        Text(
            stringResource(content.titleRes),
            color = c.ink,
            fontFamily = Montserrat,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(content.messageRes),
            color = c.inkSoft,
            fontFamily = Montserrat,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        if (content.canRetry && onRetry != null) {
            SecondaryButton(
                text = stringResource(R.string.dl_retry),
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("not_found_retry")
                    .semantics { contentDescription = retryDesc },
            )
        }
        PrimaryButton(
            text = stringResource(R.string.dl_go_home),
            onClick = onGoHome,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("not_found_home")
                .semantics { contentDescription = homeDesc },
        )
    }
}
