package com.nascon.league.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.TenantLogo
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
    val cards = homeCards(s)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm + 4.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TenantLogo(tenant = s.tenant, size = 48.dp)
                Spacer(Modifier.width(Spacing.md))
                Column {
                    Text(
                        s.tenant?.name ?: "NaSCon League",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "Signed in as ${s.user.email} · ${s.user.role.label}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (pending > 0) {
            item {
                HomeCardRow(
                    HomeCard(
                        "pending", "Pending actions",
                        "$pending change(s) waiting to sync",
                        Icons.Default.CloudSync,
                        com.nascon.league.ui.navigation.Destinations.SYNC,
                    ),
                    onNavigate,
                )
            }
        }
        items(cards, key = { it.id }) { HomeCardRow(it, onNavigate) }
    }
}

@Composable
private fun HomeCardRow(card: HomeCard, onNavigate: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .semantics { role = Role.Button }
            .clickable { onNavigate(card.route) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(card.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.titleMedium)
                Text(card.description, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
