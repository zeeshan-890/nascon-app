package com.nascon.league.matchlive

/** Ordered chat lines: server lines by sequence, local pending echoes last. Not thread safe; confine to one thread. */
class ChatTimeline(private val maxSize: Int = 500) {
    private val server = LinkedHashMap<String, ChatMessage>()
    private val pending = ArrayList<ChatMessage>()

    private fun trim() {
        if (server.size <= maxSize) return
        val keep = server.values.sortedWith(compareBy<ChatMessage>({ it.sequence }, { it.messageId }))
            .takeLast(maxSize.coerceAtLeast(0))
        server.clear()
        keep.forEach { server[it.messageId] = it }
    }

    fun mergeServer(msgs: List<ChatMessage>) {
        for (m in msgs) {
            if (server.containsKey(m.messageId)) continue
            server[m.messageId] = m
            val idx = pending.indexOfFirst { it.sender == m.sender && it.text == m.text }
            if (idx >= 0) pending.removeAt(idx)
        }
        trim()
    }

    fun addPending(localId: String, roomId: String, sender: String, text: String) {
        pending.add(
            ChatMessage(
                roomId = roomId,
                messageId = localId,
                sequence = Long.MAX_VALUE,
                sender = sender,
                text = text,
                flagged = false,
                pending = true,
            ),
        )
    }

    fun confirm(localId: String, server: ChatMessage) {
        pending.removeAll { it.messageId == localId }
        if (!this.server.containsKey(server.messageId)) {
            this.server[server.messageId] = server.copy(pending = false)
            trim()
        }
    }

    fun fail(localId: String) {
        pending.removeAll { it.messageId == localId }
    }

    fun messages(): List<ChatMessage> =
        server.values.sortedWith(compareBy<ChatMessage>({ it.sequence }, { it.messageId })) + pending.toList()
}
