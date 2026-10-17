package com.nascon.league.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.TenantLogo
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val session by vm.session.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle()
    val s = session
    if (s == null) {
        HeaderSubtitle(null)
        EmptyState(
            icon = Icons.Default.CloudSync,
            title = "Not signed in",
            message = "Sign in to see your home screen.",
            modifier = modifier.fillMaxSize(),
            actionLabel = "Sign in",
            onAction = { onNavigate(com.nascon.league.ui.navigation.Destinations.LOGIN) },
        )
        return
    }
    HeaderSubtitle("Signed in as ${s.user.email} · ${s.user.role.label}")
    val cards = homeCards(s)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            ) {
                TenantLogo(tenant = s.tenant, size = 48.dp)
                Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                    Text(
                        s.tenant?.name ?: "NaSCon League",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = Arena.colors.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        s.user.role.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Arena.colors.inkSoft,
                    )
                }
            }
        }
        if (pending > 0) {
            item {
                HomeCardRow(
                    HomeCard(
                        "pending", "Pending actions",
                        if (pending == 1) "1 change waiting to sync" else "$pending changes waiting to sync",
                        Icons.Default.CloudSync,
                        com.nascon.league.ui.navigation.Destinations.SYNC,
                    ),
                    onNavigate,
                    warn = true,
                )
            }
        }
        items(cards, key = { it.id }) { HomeCardRow(it, onNavigate) }
    }
}

@Composable
private fun HomeCardRow(card: HomeCard, onNavigate: (String) -> Unit, warn: Boolean = false) {
    val colors = Arena.colors
    ArenaCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onNavigate(card.route) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .semantics(mergeDescendants = true) { role = Role.Button },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (warn) colors.warnBg else colors.chipBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(card.icon, contentDescription = null, tint = if (warn) colors.warnFg else colors.brand)
            }
            Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                Text(
                    card.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                )
                Text(
                    card.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSoft,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.inkSoft)
        }
    }
}
