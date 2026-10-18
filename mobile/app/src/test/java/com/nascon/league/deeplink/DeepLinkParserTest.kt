package com.nascon.league.deeplink

import com.nascon.league.deeplink.DeepLinkTarget.Club
import com.nascon.league.deeplink.DeepLinkTarget.CoStream
import com.nascon.league.deeplink.DeepLinkTarget.Match
import com.nascon.league.deeplink.DeepLinkTarget.NotFound
import com.nascon.league.deeplink.DeepLinkTarget.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DeepLinkParserTest {

    private fun nf(reason: String) = NotFound(reason)

    private val id64 = "match-" + "a".repeat(58)
    private val id65 = "match-" + "a".repeat(59)

    private val table: List<Pair<String?, DeepLinkTarget>> = listOf(
        // empty-ish input
        null to nf(NotFoundReason.EMPTY),
        "" to nf(NotFoundReason.EMPTY),
        "   \t\n" to nf(NotFoundReason.EMPTY),
        // happy paths
        "nascon://entity/match-m1" to Match("match-m1"),
        "nascon://entity/club-c9" to Club("club-c9"),
        "nascon://entity/player-p_7" to Player("player-p_7"),
        "nascon://entity/cs-session-101" to CoStream("cs-session-101"),
        "nascon://stream/cs-session-101" to CoStream("cs-session-101"),
        "nascon://stream/match-m1" to Match("match-m1"),
        // case and whitespace
        "NASCON://ENTITY/match-m1" to Match("match-m1"),
        "  nascon://entity/match-m1 \n" to Match("match-m1"),
        "nascon://entity/MATCH-m1" to nf(NotFoundReason.UNSUPPORTED_COMBINATION),
        // trailing / double slash / extra segments
        "nascon://entity/match-m1/" to Match("match-m1"),
        "nascon://entity//match-m1" to nf(NotFoundReason.MISSING_ID),
        "nascon://entity/match-m1/extra" to nf(NotFoundReason.EXTRA_SEGMENTS),
        "nascon://entity/match-m1//" to nf(NotFoundReason.EXTRA_SEGMENTS),
        // missing id
        "nascon://entity/" to nf(NotFoundReason.MISSING_ID),
        "nascon://entity" to nf(NotFoundReason.MISSING_ID),
        "nascon://stream?t=5" to nf(NotFoundReason.MISSING_ID),
        // scheme problems
        "http://entity/match-m1" to nf(NotFoundReason.UNSUPPORTED_SCHEME),
        "https://nascon.example/m/abc" to nf(NotFoundReason.UNSUPPORTED_SCHEME),
        "ftp://entity/match-m1" to nf(NotFoundReason.UNSUPPORTED_SCHEME),
        "entity/match-m1" to nf(NotFoundReason.UNSUPPORTED_SCHEME),
        "nascon:entity/match-m1" to nf(NotFoundReason.UNSUPPORTED_SCHEME),
        // host problems
        "nascon://other/match-m1" to nf(NotFoundReason.UNKNOWN_HOST),
        "nascon:///match-m1" to nf(NotFoundReason.UNKNOWN_HOST),
        "nascon://user@entity/match-m1" to nf(NotFoundReason.UNKNOWN_HOST),
        "nascon://entity:80/match-m1" to nf(NotFoundReason.UNKNOWN_HOST),
        "nascon://ent ity/match-m1" to nf(NotFoundReason.UNKNOWN_HOST),
        // unsupported combinations
        "nascon://entity/foo-1" to nf(NotFoundReason.UNSUPPORTED_COMBINATION),
        "nascon://stream/club-1" to nf(NotFoundReason.UNSUPPORTED_COMBINATION),
        "nascon://stream/player-1" to nf(NotFoundReason.UNSUPPORTED_COMBINATION),
        "nascon://entity/match-" to nf(NotFoundReason.UNSUPPORTED_COMBINATION),
        // id validation
        "nascon://entity/match%2Dm1" to Match("match-m1"),
        "nascon://entity/%6Datch-m1" to Match("match-m1"),
        "nascon://entity/match-m1%00" to nf(NotFoundReason.INVALID_ID),
        "nascon://entity/match%2Fm1" to nf(NotFoundReason.INVALID_ID),
        "nascon://entity/match-%2541" to nf(NotFoundReason.INVALID_ID),
        "nascon://entity/match-%ZZ" to nf(NotFoundReason.INVALID_ID),
        "nascon://entity/match-%" to nf(NotFoundReason.INVALID_ID),
        "nascon://entity/match.m1" to nf(NotFoundReason.INVALID_ID),
        "nascon://entity/match m1" to nf(NotFoundReason.INVALID_ID),
        "nascon://entity/match-m1\tx" to nf(NotFoundReason.INVALID_ID),
        "nascon://entity/match-mé1" to nf(NotFoundReason.INVALID_ID),
        "nascon://entity/match-%C3%A9" to nf(NotFoundReason.INVALID_ID),
        "nascon://entity/match-\u0000" to nf(NotFoundReason.INVALID_ID),
        "nascon://entity/$id64" to Match(id64),
        "nascon://entity/$id65" to nf(NotFoundReason.INVALID_ID),
        "nascon://entity/match-" + "z".repeat(200) to nf(NotFoundReason.INVALID_ID),
        // query and fragment
        "nascon://entity/match-m1?t=90" to Match("match-m1", 90),
        "nascon://entity/match-m1/?t=90" to Match("match-m1", 90),
        "nascon://entity/match-m1?t=0" to Match("match-m1", 0),
        "nascon://entity/match-m1?t=604800" to Match("match-m1", 604800),
        "nascon://entity/match-m1?t=604801" to Match("match-m1"),
        "nascon://entity/match-m1?t=-5" to Match("match-m1"),
        "nascon://entity/match-m1?t=abc" to Match("match-m1"),
        "nascon://entity/match-m1?t=" to Match("match-m1"),
        "nascon://entity/match-m1?t" to Match("match-m1"),
        "nascon://entity/match-m1?t=99999999999999999999" to Match("match-m1"),
        "nascon://entity/match-m1?t=1.5" to Match("match-m1"),
        "nascon://entity/match-m1?t=10&t=20" to Match("match-m1", 10),
        "nascon://entity/match-m1?x=1&t=30&y=2" to Match("match-m1", 30),
        "nascon://entity/match-m1?x=1" to Match("match-m1"),
        "nascon://entity/match-m1#frag" to Match("match-m1"),
        "nascon://entity/match-m1?t=15#frag" to Match("match-m1", 15),
        "nascon://stream/match-m1?t=7" to Match("match-m1", 7),
        "nascon://entity/club-c1?t=7" to Club("club-c1"),
        "nascon://entity/cs-session-1?t=7" to CoStream("cs-session-1"),
    )

    @Test
    fun tableOfInputs() {
        assertTrue("need at least 45 cases, have ${table.size}", table.size >= 45)
        assertEquals("inputs must be distinct", table.size, table.map { it.first }.toSet().size)
        for ((input, expected) in table) {
            assertEquals("input: $input", expected, DeepLinkParser.parse(input))
        }
    }

    @Test
    fun neverThrowsOnRandomStrings() {
        val random = Random(42)
        val pool = "abcXYZ019-_.%/?#:@& =+é中\u0000\u0007\t\n".toList() +
            listOf('%', '2', '5', 'F', '0', '/', '?', '#', ':', '@')
        val prefixes = listOf("", "nascon://", "nascon://entity/", "nascon://stream/match-", "NASCON://entity/match-m1?t=")
        repeat(2000) {
            val body = buildString { repeat(random.nextInt(0, 60)) { append(pool[random.nextInt(pool.size)]) } }
            val input = prefixes[random.nextInt(prefixes.size)] + body
            val result = DeepLinkParser.parse(input)
            assertTrue("input: $input", result is DeepLinkTarget)
        }
    }
}
