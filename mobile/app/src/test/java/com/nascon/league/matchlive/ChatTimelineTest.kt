package com.nascon.league.matchlive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineTest {

    private fun m(id: String, seq: Long, sender: String = "u", text: String = "t$seq", flagged: Boolean = false) =
        ChatMessage("chat-m1", id, seq, sender, text, flagged)

    @Test fun shuffledArrivalEndsSorted() {
        val t = ChatTimeline()
        t.mergeServer(listOf(m("c", 3), m("a", 1)))
        t.mergeServer(listOf(m("b", 2), m("e", 5), m("d", 4)))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), t.messages().map { it.sequence })
    }

    @Test fun duplicateMessageIdIgnored() {
        val t = ChatTimeline()
        t.mergeServer(listOf(m("a", 1)))
        t.mergeServer(listOf(m("a", 1), m("a", 9, text = "other")))
        assertEquals(1, t.messages().size)
        assertEquals("t1", t.messages()[0].text)
    }

    @Test fun tiesBrokenByMessageId() {
        val t = ChatTimeline()
        t.mergeServer(listOf(m("z", 1), m("b", 1)))
        assertEquals(listOf("b", "z"), t.messages().map { it.messageId })
    }

    @Test fun pendingShownLast() {
        val t = ChatTimeline()
        t.addPending("l1", "chat-m1", "me", "hello")
        t.mergeServer(listOf(m("a", 1), m("b", 2)))
        val msgs = t.messages()
        assertEquals("l1", msgs.last().messageId)
        assertTrue(msgs.last().pending)
        assertEquals(Long.MAX_VALUE, msgs.last().sequence)
    }

    @Test fun confirmReplacesPending() {
        val t = ChatTimeline()
        t.addPending("l1", "chat-m1", "me", "hello")
        t.confirm("l1", m("s1", 10, "me", "hello"))
        val msgs = t.messages()
        assertEquals(1, msgs.size)
        assertEquals("s1", msgs[0].messageId)
        assertFalse(msgs[0].pending)
    }

    @Test fun confirmAfterServerAlreadyArrivedDropsPending() {
        val t = ChatTimeline()
        t.addPending("l1", "chat-m1", "me", "hello")
        t.mergeServer(listOf(m("s1", 10, "me", "hello")))
        t.confirm("l1", m("s1", 10, "me", "hello"))
        assertEquals(listOf("s1"), t.messages().map { it.messageId })
    }

    @Test fun confirmWhenMessageIdAlreadyPresentDropsPendingWithDifferentText() {
        val t = ChatTimeline()
        t.mergeServer(listOf(m("s1", 10, "me", "masked ***")))
        t.addPending("l1", "chat-m1", "me", "bad word")
        t.confirm("l1", m("s1", 10, "me", "masked ***"))
        assertEquals(1, t.messages().size)
        assertEquals("masked ***", t.messages()[0].text)
    }

    @Test fun echoRemovesPending() {
        val t = ChatTimeline()
        t.addPending("l1", "chat-m1", "me", "hi")
        t.mergeServer(listOf(m("s1", 1, "me", "hi")))
        assertEquals(1, t.messages().size)
        assertFalse(t.messages()[0].pending)
    }

    @Test fun echoFromOtherSenderKeepsPending() {
        val t = ChatTimeline()
        t.addPending("l1", "chat-m1", "me", "hi")
        t.mergeServer(listOf(m("s1", 1, "other", "hi")))
        assertEquals(2, t.messages().size)
        assertTrue(t.messages().last().pending)
    }

    @Test fun echoWithDifferentTextKeepsPending() {
        val t = ChatTimeline()
        t.addPending("l1", "chat-m1", "me", "hi")
        t.mergeServer(listOf(m("s1", 1, "me", "hi!")))
        assertEquals(2, t.messages().size)
    }

    @Test fun echoRemovesOnlyOnePendingForIdenticalText() {
        val t = ChatTimeline()
        t.addPending("l1", "chat-m1", "me", "hi")
        t.addPending("l2", "chat-m1", "me", "hi")
        t.mergeServer(listOf(m("s1", 1, "me", "hi")))
        assertEquals(listOf("s1", "l2"), t.messages().map { it.messageId })
    }

    @Test fun failRemovesPending() {
        val t = ChatTimeline()
        t.addPending("l1", "chat-m1", "me", "hi")
        t.fail("l1")
        assertTrue(t.messages().isEmpty())
    }

    @Test fun failUnknownIdIsNoOp() {
        val t = ChatTimeline()
        t.mergeServer(listOf(m("a", 1)))
        t.fail("nope")
        assertEquals(1, t.messages().size)
    }

    @Test fun maxSizeTrimsOldest() {
        val t = ChatTimeline(maxSize = 3)
        t.mergeServer((1L..6L).map { m("id$it", it) })
        assertEquals(listOf(4L, 5L, 6L), t.messages().map { it.sequence })
    }

    @Test fun maxSizeTrimsAcrossMerges() {
        val t = ChatTimeline(maxSize = 2)
        t.mergeServer(listOf(m("a", 5), m("b", 6)))
        t.mergeServer(listOf(m("c", 1)))
        assertEquals(listOf(5L, 6L), t.messages().map { it.sequence })
    }

    @Test fun maxSizeDoesNotDropPending() {
        val t = ChatTimeline(maxSize = 1)
        t.addPending("l1", "chat-m1", "me", "x")
        t.mergeServer(listOf(m("a", 1), m("b", 2)))
        assertEquals(listOf("b", "l1"), t.messages().map { it.messageId })
    }

    @Test fun emojiPreserved() {
        val t = ChatTimeline()
        val s = "Goal! 😀⚽🇵🇰"
        t.mergeServer(listOf(m("a", 1, text = s)))
        assertEquals(s, t.messages()[0].text)
    }

    @Test fun urduPreserved() {
        val t = ChatTimeline()
        val s = "شاندار کھیل"
        t.mergeServer(listOf(m("a", 1, text = s)))
        assertEquals(s, t.messages()[0].text)
    }

    @Test fun veryLongWordPreserved() {
        val t = ChatTimeline()
        val s = "a".repeat(5000)
        t.mergeServer(listOf(m("a", 1, text = s)))
        assertEquals(5000, t.messages()[0].text.length)
    }

    @Test fun maskedTextPassthrough() {
        val t = ChatTimeline()
        t.mergeServer(listOf(m("a", 1, text = "***", flagged = true)))
        assertEquals("***", t.messages()[0].text)
    }

    @Test fun flaggedFlagPreserved() {
        val t = ChatTimeline()
        t.mergeServer(listOf(m("a", 1, flagged = true), m("b", 2)))
        assertTrue(t.messages()[0].flagged)
        assertFalse(t.messages()[1].flagged)
    }

    @Test fun twoPendingKeepInsertionOrder() {
        val t = ChatTimeline()
        t.addPending("l2", "chat-m1", "me", "second-first")
        t.addPending("l1", "chat-m1", "me", "first-second")
        assertEquals(listOf("l2", "l1"), t.messages().map { it.messageId })
    }

    @Test fun emptyTimeline() = assertTrue(ChatTimeline().messages().isEmpty())

    @Test fun pendingKeepsRoomAndSender() {
        val t = ChatTimeline()
        t.addPending("l1", "chat-m9", "me", "x")
        val p = t.messages()[0]
        assertEquals("chat-m9", p.roomId)
        assertEquals("me", p.sender)
        assertFalse(p.flagged)
    }
}
