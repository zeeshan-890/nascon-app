package com.nascon.league.ui.matchlive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nascon.league.R
import com.nascon.league.matchlive.ChatMessage
import com.nascon.league.matchlive.MatchLiveTags
import com.nascon.league.ui.components.ArenaTextField
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing

private const val MAX_CHARS = 500

/** Shared chat panel (Match Live and Co-Stream viewer). Order is exactly as given; the caller sorts by server sequence. */
@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    canSend: Boolean,
    sending: Boolean,
    guest: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Arena.colors
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    var seenCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(messages.lastOrNull()?.messageId, messages.size) {
        if (messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val wasAtBottom = seenCount == 0 || lastVisible >= seenCount - 1
            if (wasAtBottom) listState.scrollToItem(messages.lastIndex)
        }
        seenCount = messages.size
    }

    val canPress = canSend && draft.isNotBlank() && !sending
    val submit = {
        if (canPress) {
            onSend(draft.trim())
            if (!guest) draft = ""
        }
    }

    Column(modifier = modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = colors.inkSoft, modifier = Modifier.size(32.dp))
                    Text(
                        stringResource(R.string.chat_empty_message),
                        color = colors.inkMid,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = Spacing.sm, start = Spacing.md, end = Spacing.md),
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag(MatchLiveTags.CHAT_LIST),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(messages, key = { it.messageId }) { m ->
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs)) {
                        Text(
                            m.sender,
                            color = colors.ink,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth(),
                            style = TextStyle(textDirection = TextDirection.Content),
                        )
                        Text(
                            m.text,
                            color = colors.ink,
                            fontSize = 14.sp,
                            softWrap = true,
                            modifier = Modifier.fillMaxWidth(),
                            style = TextStyle(textDirection = TextDirection.Content),
                        )
                        if (m.pending) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Schedule, contentDescription = null, tint = colors.inkSoft, modifier = Modifier.size(14.dp))
                                Text(
                                    stringResource(R.string.chat_sending),
                                    color = colors.inkSoft,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = Spacing.xs),
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            ArenaTextField(
                value = draft,
                onValueChange = { draft = it.take(MAX_CHARS) },
                label = stringResource(R.string.chat_input_label),
                placeholder = stringResource(R.string.chat_input_placeholder),
                enabled = canSend,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.weight(1f).testTag(MatchLiveTags.CHAT_INPUT),
            )
            val sendDesc = stringResource(R.string.chat_send_description)
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 52.dp)
                    .testTag(MatchLiveTags.CHAT_SEND)
                    .clickable(enabled = canPress, role = Role.Button) { submit() }
                    .semantics { contentDescription = sendDesc },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = if (canPress) colors.brand else colors.inkSoft,
                )
            }
        }
        val hint = when {
            !canSend -> stringResource(R.string.chat_closed_hint)
            guest -> stringResource(R.string.chat_guest_hint)
            else -> null
        }
        if (hint != null) {
            Text(hint, color = colors.inkMid, fontSize = 12.sp, modifier = Modifier.padding(top = Spacing.xs, start = Spacing.xs))
        }
    }
}
