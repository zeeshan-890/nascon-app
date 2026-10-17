package com.nascon.league.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nascon.league.R
import com.nascon.league.core.time.RelativeTime
import com.nascon.league.core.time.UpdatedAge
import kotlinx.coroutines.delay
import java.time.Instant

@Composable
fun LastUpdatedLabel(instant: Instant?, modifier: Modifier = Modifier) {
    if (instant == null) return
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(instant) {
        while (true) {
            now = Instant.now()
            delay(60_000L)
        }
    }
    val text = when (val age = RelativeTime.age(instant, now)) {
        UpdatedAge.JustNow -> stringResource(R.string.f2_updated_just_now)
        is UpdatedAge.MinutesAgo -> stringResource(R.string.f2_updated_minutes, age.minutes)
        is UpdatedAge.HoursAgo -> stringResource(R.string.f2_updated_hours, age.hours)
        is UpdatedAge.Absolute -> stringResource(R.string.f2_updated_absolute, age.formatted)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { contentDescription = text },
    )
}
