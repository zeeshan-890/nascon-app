package com.nascon.league.ui.navigation

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** Lets screens customise the shared app-shell header (subtitle, extra content, hidden). */
class HeaderController {
    var subtitle by mutableStateOf<String?>(null)
    var extra by mutableStateOf<(@Composable ColumnScope.() -> Unit)?>(null)
    var expanded by mutableStateOf(false)
    var hidden by mutableStateOf(false)

    fun reset() {
        subtitle = null
        extra = null
        expanded = false
        hidden = false
    }
}

val LocalHeaderController = staticCompositionLocalOf<HeaderController> { HeaderController() }

@Composable
fun HeaderSubtitle(text: String?) {
    val c = LocalHeaderController.current
    DisposableEffect(c, text) {
        c.subtitle = text
        onDispose { if (c.subtitle == text) c.subtitle = null }
    }
}

@Composable
fun HeaderExtra(expanded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalHeaderController.current
    val latest by rememberUpdatedState(content)
    val holder: @Composable ColumnScope.() -> Unit = androidx.compose.runtime.remember { { latest() } }
    DisposableEffect(c, expanded) {
        c.extra = holder
        c.expanded = expanded
        onDispose {
            if (c.extra === holder) {
                c.extra = null
                c.expanded = false
            }
        }
    }
}

@Composable
fun HeaderHidden(hidden: Boolean = true) {
    val c = LocalHeaderController.current
    DisposableEffect(c, hidden) {
        c.hidden = hidden
        onDispose { if (c.hidden == hidden) c.hidden = false }
    }
}
