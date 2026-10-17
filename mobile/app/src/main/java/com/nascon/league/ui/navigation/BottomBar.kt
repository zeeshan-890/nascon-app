package com.nascon.league.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nascon.league.ui.theme.Arena

data class TabItem(val route: String, val label: String, val icon: ImageVector)

val tabItems = listOf(
    TabItem(Destinations.HOME, "Home", Icons.Default.Home),
    TabItem(Destinations.FIXTURES, "Fixtures", Icons.Default.SportsSoccer),
    TabItem(Destinations.STANDINGS, "Standings", Icons.Default.Leaderboard),
    TabItem(Destinations.STORE, "Store", Icons.Default.ShoppingBag),
    TabItem(Destinations.CLUBS, "Club", Icons.Default.Groups),
)

/** Route without query part, so "fixtures?filter={filter}" counts as the Fixtures tab. */
fun baseRoute(route: String?): String? = route?.substringBefore('?')

fun isTabRoute(route: String?): Boolean = baseRoute(route).let { r -> tabItems.any { it.route == r } }

@Composable
fun BottomBar(
    currentRoute: String?,
    onTabClick: (TabItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val a = Arena.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.White)
            .selectableGroup(),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(a.cardBorder))
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            tabItems.forEach { tab ->
                val selected = baseRoute(currentRoute) == tab.route
                val tint = if (selected) a.brand else a.inkSoft
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onTabClick(tab) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) a.chipBg else Color.Transparent)
                            .padding(horizontal = 18.dp, vertical = 4.dp),
                    ) {
                        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    }
                    Text(
                        tab.label,
                        color = tint,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
