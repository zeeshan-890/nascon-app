package com.nascon.league.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascon.league.R
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.navigation.HeaderExtra
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.player.InitialsAvatar
import com.nascon.league.ui.player.InfoRow
import com.nascon.league.ui.player.initialsFromEmail
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing

/** B23: account summary with links to settings, sync, orders, notifications and sign out. */
@Composable
fun ProfileScreen(
    onOpenSettings: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenOrders: () -> Unit,
    onOpenNotifications: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    val user = session?.user
    val email = user?.email.orEmpty()
    val roleLabel = user?.role?.label
    val tenantName = session?.tenant?.name?.takeIf { it.isNotBlank() }
    HeaderSubtitle(stringResource(R.string.np_profile_title))
    HeaderExtra {
        Column(
            Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            InitialsAvatar(initialsFromEmail(email), 72.dp, onGradient = true)
            if (email.isNotEmpty()) {
                Text(
                    email,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.sm).semantics { heading() },
                )
            }
            if (roleLabel != null) {
                StatusChip(roleLabel, Icons.Default.VerifiedUser, modifier = Modifier.padding(top = Spacing.sm))
            }
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (email.isNotEmpty()) item(key = "email") { InfoRow(stringResource(R.string.np_profile_email), email) }
        if (tenantName != null) item(key = "org") { InfoRow(stringResource(R.string.np_profile_org), tenantName) }
        item(key = "notifications") {
            LinkRow(Icons.Default.Notifications, stringResource(R.string.np_profile_notifications), onOpenNotifications)
        }
        item(key = "orders") { LinkRow(Icons.Default.Receipt, stringResource(R.string.np_profile_orders), onOpenOrders) }
        item(key = "sync") { LinkRow(Icons.Default.Sync, stringResource(R.string.np_profile_sync), onOpenSync) }
        item(key = "settings") { LinkRow(Icons.Default.Settings, stringResource(R.string.np_profile_settings), onOpenSettings) }
        item(key = "signout") {
            SecondaryButton(
                text = stringResource(R.string.np_profile_sign_out),
                onClick = onSignOut,
                destructive = true,
                icon = Icons.AutoMirrored.Filled.Logout,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
            )
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ArenaCard(Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(icon, contentDescription = null, tint = Arena.colors.brand, modifier = Modifier.size(24.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = Arena.colors.ink,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Arena.colors.inkSoft)
        }
    }
}
