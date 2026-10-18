package com.nascon.league.guest

import com.nascon.league.ui.guest.SignInOrigin
import com.nascon.league.ui.guest.SignInOriginStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignInOriginTest {
    private fun roundTrip(route: String?, secs: Int?): SignInOrigin? {
        val a = SignInOriginStore()
        a.record(route, secs)
        val b = SignInOriginStore()
        b.restore(a.save())
        return b.peek()
    }

    @Test fun startsEmpty() {
        assertNull(SignInOriginStore().peek())
    }

    @Test fun recordThenPeekKeeps() {
        val s = SignInOriginStore()
        s.record("match/1", 30)
        assertEquals(SignInOrigin("match/1", 30), s.peek())
        assertEquals(SignInOrigin("match/1", 30), s.peek())
    }

    @Test fun consumeReturnsAndClears() {
        val s = SignInOriginStore()
        s.record("match/1", 5)
        assertEquals(SignInOrigin("match/1", 5), s.consume())
        assertNull(s.peek())
    }

    @Test fun consumeTwiceSecondIsNull() {
        val s = SignInOriginStore()
        s.record("a", null)
        s.consume()
        assertNull(s.consume())
    }

    @Test fun negativeSecondsStoredAsNull() {
        val s = SignInOriginStore()
        s.record("a", -4)
        assertNull(s.peek()?.playbackSeconds)
    }

    @Test fun roundTripSimple() {
        assertEquals(SignInOrigin("match/1", 90), roundTrip("match/1", 90))
    }

    @Test fun roundTripSpecialChars() {
        val r = "match/1?t=30&x=a|b"
        assertEquals(SignInOrigin(r, 12), roundTrip(r, 12))
    }

    @Test fun roundTripNonAscii() {
        assertEquals(SignInOrigin("مباراة/é", 1), roundTrip("مباراة/é", 1))
    }

    @Test fun roundTripNullSeconds() {
        assertEquals(SignInOrigin("x", null), roundTrip("x", null))
    }

    @Test fun roundTripNullRoute() {
        assertEquals(SignInOrigin(null, 7), roundTrip(null, 7))
    }

    @Test fun saveEmptyIsNull() {
        assertNull(SignInOriginStore().save())
    }

    @Test fun restoreGarbageGivesNull() {
        val s = SignInOriginStore()
        s.record("a", 1)
        for (g in listOf("garbage", "a|b|c", "%ZZ|1", "x|notnum", "", null, "|-3")) {
            s.restore(g)
            assertNull("for $g", s.peek())
        }
    }
}
