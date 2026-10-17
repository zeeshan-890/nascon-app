package com.nascon.league.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import com.nascon.league.domain.model.Tenant

/** Kept for source compatibility; now renders the Arena360 gradient header. */
@Composable
fun AppTopBar(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    tenant: Tenant?,
    actions: @Composable RowScope.() -> Unit = {},
) {
    ArenaHeader(
        title = title,
        onBack = if (showBack) onBack else null,
        tenant = tenant,
        trailing = actions,
    )
}
