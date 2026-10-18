package com.nascon.league.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDeepLinkTest {

    private var now = 0L
    private fun holder() = PendingDeepLink({ now }, 1000)

    @Test fun offerPeekConsumeCycle() {
        val h = holder()
        assertFalse(h.hasPending())
        assertTrue(h.offer("nascon://match/1"))
        assertTrue(h.hasPending())
        assertEquals("nascon://match/1", h.peek())
        assertEquals("nascon://match/1", h.peek())
        assertEquals("nascon://match/1", h.consume())
        assertFalse(h.hasPending())
        assertNull(h.peek())
    }

    @Test fun consumeTwiceReturnsNull() {
        val h = holder()
        h.offer("nascon://club/1")
        assertEquals("nascon://club/1", h.consume())
        assertNull(h.consume())
    }

    @Test fun consumeWithNothingPending() = assertNull(holder().consume())

    @Test fun nullIgnored() {
        val h = holder()
        assertFalse(h.offer(null))
        assertFalse(h.hasPending())
    }

    @Test fun emptyIgnored() = assertFalse(holder().offer(""))

    @Test fun blankIgnored() {
        val h = holder()
        assertFalse(h.offer("   \t"))
        assertFalse(h.hasPending())
    }

    @Test fun blankDoesNotClearExistingPending() {
        val h = holder()
        h.offer("nascon://match/1")
        assertFalse(h.offer(" "))
        assertEquals("nascon://match/1", h.peek())
    }

    @Test fun duplicateWithinWindowIgnored() {
        val h = holder()
        now = 0
        assertTrue(h.offer("nascon://match/1"))
        now = 999
        assertFalse(h.offer("nascon://match/1"))
    }

    @Test fun duplicateAtWindowBoundaryAccepted() {
        val h = holder()
        now = 0
        h.offer("nascon://match/1")
        h.consume()
        now = 1000
        assertTrue(h.offer("nascon://match/1"))
    }

    @Test fun duplicateAfterWindowAccepted() {
        val h = holder()
        now = 0
        h.offer("nascon://match/1")
        h.consume()
        now = 1001
        assertTrue(h.offer("nascon://match/1"))
        assertEquals("nascon://match/1", h.peek())
    }

    @Test fun duplicateIgnoredEvenAfterConsume() {
        val h = holder()
        now = 0
        h.offer("nascon://match/1")
        h.consume()
        now = 500
        assertFalse(h.offer("nascon://match/1"))
        assertFalse(h.hasPending())
    }

    @Test fun duplicateComparesTrimmed() {
        val h = holder()
        now = 0
        h.offer("nascon://match/1")
        now = 10
        assertFalse(h.offer("  nascon://match/1 "))
    }

    @Test fun duplicateIsCaseSensitive() {
        val h = holder()
        now = 0
        h.offer("nascon://match/abc")
        now = 10
        assertTrue(h.offer("nascon://match/ABC"))
        assertEquals("nascon://match/ABC", h.peek())
    }

    @Test fun differentUriWithinWindowReplacesPending() {
        val h = holder()
        now = 0
        h.offer("nascon://match/1")
        now = 100
        assertTrue(h.offer("nascon://club/2"))
        assertEquals("nascon://club/2", h.consume())
        assertNull(h.consume())
    }

    @Test fun storedValueIsTrimmed() {
        val h = holder()
        h.offer("  nascon://player/9  ")
        assertEquals("nascon://player/9", h.peek())
    }

    @Test fun saveReturnsPending() {
        val h = holder()
        h.offer("nascon://match/1")
        assertEquals("nascon://match/1", h.save())
    }

    @Test fun saveNullWhenNone() = assertNull(holder().save())

    @Test fun saveNullAfterConsume() {
        val h = holder()
        h.offer("nascon://match/1")
        h.consume()
        assertNull(h.save())
    }

    @Test fun processDeathRoundTrip() {
        val before = holder()
        before.offer("nascon://match/1?t=30")
        val saved = before.save()
        val after = holder()
        after.restore(saved)
        assertTrue(after.hasPending())
        assertEquals("nascon://match/1?t=30", after.peek())
        assertEquals("nascon://match/1?t=30", after.consume())
    }

    @Test fun restoreNullClears() {
        val h = holder()
        h.offer("nascon://match/1")
        h.restore(null)
        assertFalse(h.hasPending())
    }

    @Test fun restoreBlankClears() {
        val h = holder()
        h.offer("nascon://match/1")
        h.restore("   ")
        assertFalse(h.hasPending())
    }

    @Test fun restoreReplacesPending() {
        val h = holder()
        h.offer("nascon://match/1")
        h.restore("nascon://club/2")
        assertEquals("nascon://club/2", h.peek())
    }

    // restore() does not record a last-offered uri/timestamp, so the dedupe window never
    // applies to a restored link: offering the same uri right after restore returns true.
    @Test fun offerSameUriAfterRestoreIsNotDeduped() {
        val h = holder()
        now = 0
        h.restore("nascon://match/1")
        now = 10
        assertTrue(h.offer("nascon://match/1"))
        assertEquals("nascon://match/1", h.peek())
    }

    @Test fun restoredLinkNotBlockedByWindow() {
        val h = holder()
        now = 5
        h.restore("nascon://match/1")
        assertEquals("nascon://match/1", h.consume())
    }

    @Test fun customWindowRespected() {
        var t = 0L
        val h = PendingDeepLink({ t }, 50)
        h.offer("nascon://a/1")
        t = 49
        assertFalse(h.offer("nascon://a/1"))
        t = 50
        assertTrue(h.offer("nascon://a/1"))
    }

    // DeepLinkGate

    @Test fun gateHoldsOnLogin() = assertTrue(DeepLinkGate.shouldHold("login"))
    @Test fun gateHoldsOnLoginWithArgs() = assertTrue(DeepLinkGate.shouldHold("login?x=1"))
    @Test fun gateHoldsOnOnboarding() = assertTrue(DeepLinkGate.shouldHold("onboarding"))
    @Test fun gateHoldsOnNullRoute() = assertTrue(DeepLinkGate.shouldHold(null))
    @Test fun gateOpenOnHome() = assertFalse(DeepLinkGate.shouldHold("home"))
    @Test fun gateOpenOnMatch() = assertFalse(DeepLinkGate.shouldHold("match/abc"))
}
