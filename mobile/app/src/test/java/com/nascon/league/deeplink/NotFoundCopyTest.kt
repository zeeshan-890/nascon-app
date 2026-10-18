package com.nascon.league.deeplink

import com.nascon.league.ui.deeplink.NotFoundCopy
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotFoundCopyTest {
    private val all = listOf(
        NotFoundReason.EMPTY, NotFoundReason.UNSUPPORTED_SCHEME, NotFoundReason.UNKNOWN_HOST,
        NotFoundReason.MISSING_ID, NotFoundReason.INVALID_ID, NotFoundReason.UNSUPPORTED_COMBINATION,
        NotFoundReason.EXTRA_SEGMENTS, NotFoundReason.ENTITY_MISSING,
    )

    @Test fun everyReasonMapsToContent() {
        all.forEach { r ->
            val c = NotFoundCopy.contentFor(r)
            assertNotNull(r, c)
            assertTrue(c.messageRes != 0 && c.titleRes != 0)
        }
    }

    @Test fun distinctMessages() {
        val ids = listOf(
            NotFoundReason.INVALID_ID, NotFoundReason.UNKNOWN_HOST, NotFoundReason.MISSING_ID,
            NotFoundReason.EXTRA_SEGMENTS, NotFoundReason.EMPTY, NotFoundReason.ENTITY_MISSING, "other",
        ).map { NotFoundCopy.contentFor(it).messageRes }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun unsupportedReasonsShareMessage() {
        val a = NotFoundCopy.contentFor(NotFoundReason.UNKNOWN_HOST).messageRes
        assertEquals(a, NotFoundCopy.contentFor(NotFoundReason.UNSUPPORTED_COMBINATION).messageRes)
        assertEquals(a, NotFoundCopy.contentFor(NotFoundReason.UNSUPPORTED_SCHEME).messageRes)
        assertNotEquals(a, NotFoundCopy.contentFor(NotFoundReason.MISSING_ID).messageRes)
    }

    @Test fun retryOnlyForEntityMissingAndFallback() {
        all.forEach { r ->
            assertEquals(r, r == NotFoundReason.ENTITY_MISSING, NotFoundCopy.contentFor(r).canRetry)
        }
        assertTrue(NotFoundCopy.contentFor(null).canRetry)
        assertTrue(NotFoundCopy.contentFor("weird-reason").canRetry)
    }

    @Test fun nullAndUnknownUseGenericContent() {
        val g = NotFoundCopy.contentFor(null)
        assertEquals(g, NotFoundCopy.contentFor("weird-reason"))
        assertFalse(all.any { NotFoundCopy.contentFor(it).messageRes == g.messageRes })
    }

    @Test fun neverThrowsForRandomStrings() {
        val rnd = Random(42)
        repeat(500) {
            val s = String(CharArray(rnd.nextInt(40)) { (rnd.nextInt(0x7e - 0x20) + 0x20).toChar() })
            assertNotNull(NotFoundCopy.contentFor(s))
        }
    }
}
