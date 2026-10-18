package com.nascon.league.ui.useradmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.useradmin.canAdminister
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat
import com.nascon.league.ui.theme.Spacing

/** G01. Content only; navigation targets are passed in so the graph owns the routes. */
@Composable
fun AdminHomeScreen(
    onUsers: () -> Unit,
    onClubs: () -> Unit,
    onSports: () -> Unit,
    onLeagues: () -> Unit,
    onAudit: () -> Unit,
    onAccess: () -> Unit,
    onStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    if (!canAdminister(session)) {
        EmptyState(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.ua_not_allowed_title),
            message = stringResource(R.string.ua_not_allowed_message),
            modifier = modifier,
        )
        return
    }
    HeaderSubtitle(stringResource(R.string.ua_home_subtitle))
    val vm: AdminHomeViewModel = viewModel(factory = AdminHomeViewModel.factory(container))
    val userCount by vm.userCount.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AdminTile(stringResource(R.string.ua_tile_users), Icons.Default.People, userCount, onUsers, Modifier.weight(1f))
            AdminTile(stringResource(R.string.ua_tile_clubs), Icons.Default.Groups, null, onClubs, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AdminTile(stringResource(R.string.ua_tile_sports), Icons.Default.SportsSoccer, null, onSports, Modifier.weight(1f))
            AdminTile(stringResource(R.string.ua_tile_leagues), Icons.Default.EmojiEvents, null, onLeagues, Modifier.weight(1f))
        }
        AdminLinkRow(stringResource(R.string.ua_row_users), Icons.Default.AdminPanelSettings, onUsers)
        AdminLinkRow(stringResource(R.string.ua_row_sports), Icons.Default.Storefront, onSports)
        AdminLinkRow(stringResource(R.string.ua_row_audit), Icons.Default.History, onAudit)
        AdminLinkRow(stringResource(R.string.ua_row_access), Icons.Default.Lock, onAccess)
        AdminLinkRow(stringResource(R.string.ua_row_status), Icons.Default.MonitorHeart, onStatus)
    }
}

@Composable
private fun AdminTile(label: String, icon: ImageVector, count: Int?, onClick: () -> Unit, modifier: Modifier) {
    val c = Arena.colors
    ArenaCard(modifier = modifier.heightIn(min = 92.dp), onClick = onClick) {
        Text(label, color = c.ink, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        if (count != null) {
            Text(
                count.toString(), color = c.brand, fontFamily = Montserrat, fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Icon(icon, contentDescription = null, tint = c.brand, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun AdminLinkRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    val c = Arena.colors
    ArenaCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = c.brand)
            Text(
                label, color = c.ink, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = c.inkSoft)
        }
    }
}
