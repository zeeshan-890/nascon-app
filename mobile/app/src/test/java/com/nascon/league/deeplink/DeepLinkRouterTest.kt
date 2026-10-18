package com.nascon.league.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkRouterTest {

    private class BackStack(routes: List<String>) {
        private val stack = routes.toMutableList()
        fun pop(): String? = if (stack.isEmpty()) null else stack.removeAt(stack.size - 1)
        fun top(): String? = stack.lastOrNull()
        fun isEmpty() = stack.isEmpty()
    }

    private val allTargets = listOf(
        DeepLinkTarget.Match("m1", 30),
        DeepLinkTarget.Match("m2"),
        DeepLinkTarget.Club("c1"),
        DeepLinkTarget.Player("p1"),
        DeepLinkTarget.CoStream("s1"),
        DeepLinkTarget.NotFound(NotFoundReason.EMPTY),
    )

    @Test fun inProgressIsLive() = assertEquals(MatchScreen.LIVE, DeepLinkRouter.matchScreenFor("in_progress"))
    @Test fun upcomingIsPreview() = assertEquals(MatchScreen.PREVIEW, DeepLinkRouter.matchScreenFor("upcoming"))
    @Test fun completedIsVod() = assertEquals(MatchScreen.VOD, DeepLinkRouter.matchScreenFor("completed"))
    @Test fun postponedIsNotice() = assertEquals(MatchScreen.NOTICE, DeepLinkRouter.matchScreenFor("postponed"))
    @Test fun cancelledIsNotice() = assertEquals(MatchScreen.NOTICE, DeepLinkRouter.matchScreenFor("cancelled"))
    @Test fun abandonedIsNotice() = assertEquals(MatchScreen.NOTICE, DeepLinkRouter.matchScreenFor("abandoned"))
    @Test fun nullIsShell() = assertEquals(MatchScreen.LIVE_SHELL, DeepLinkRouter.matchScreenFor(null))
    @Test fun emptyIsShell() = assertEquals(MatchScreen.LIVE_SHELL, DeepLinkRouter.matchScreenFor(""))
    @Test fun blankIsShell() = assertEquals(MatchScreen.LIVE_SHELL, DeepLinkRouter.matchScreenFor("   "))
    @Test fun unknownIsShell() = assertEquals(MatchScreen.LIVE_SHELL, DeepLinkRouter.matchScreenFor("weird"))
    @Test fun messyCaseAndSpacesNormalised() = assertEquals(MatchScreen.LIVE, DeepLinkRouter.matchScreenFor("  In_Progress "))
    @Test fun upperCaseNormalised() = assertEquals(MatchScreen.LIVE, DeepLinkRouter.matchScreenFor("IN_PROGRESS"))
    @Test fun upperCompletedNormalised() = assertEquals(MatchScreen.VOD, DeepLinkRouter.matchScreenFor("Completed"))

    @Test fun matchPlan() {
        val p = DeepLinkRouter.plan(DeepLinkTarget.Match("abc", 90))
        assertEquals(listOf("home", "match/abc"), p.routes)
        assertEquals(90, p.startSeconds)
    }

    @Test fun clubPlan() = assertEquals(listOf("home", "club/c1"), DeepLinkRouter.plan(DeepLinkTarget.Club("c1")).routes)
    @Test fun playerPlan() = assertEquals(listOf("home", "player/p1"), DeepLinkRouter.plan(DeepLinkTarget.Player("p1")).routes)
    @Test fun coStreamPlan() = assertEquals(listOf("home", "costream/s1"), DeepLinkRouter.plan(DeepLinkTarget.CoStream("s1")).routes)

    @Test fun notFoundPlan() {
        val p = DeepLinkRouter.plan(DeepLinkTarget.NotFound(NotFoundReason.MISSING_ID))
        assertEquals(listOf("home", "notfound/missing_id"), p.routes)
        assertNull(p.startSeconds)
    }

    @Test fun planAlwaysStartsWithHome() {
        allTargets.forEach { assertEquals("home", DeepLinkRouter.plan(it).routes.first()) }
    }

    @Test fun backStackLengthIsTwo() {
        allTargets.forEach { assertEquals(2, DeepLinkRouter.plan(it).routes.size) }
    }

    @Test fun noConsecutiveDuplicates() {
        allTargets.forEach {
            val r = DeepLinkRouter.plan(it).routes
            for (i in 1 until r.size) assertTrue(r[i] != r[i - 1])
        }
    }

    @Test fun startSecondsNullWhenAbsent() = assertNull(DeepLinkRouter.plan(DeepLinkTarget.Match("m")).startSeconds)
    @Test fun startSecondsZeroPropagates() = assertEquals(0, DeepLinkRouter.plan(DeepLinkTarget.Match("m", 0)).startSeconds)
    @Test fun startSecondsValuePropagates() = assertEquals(125, DeepLinkRouter.plan(DeepLinkTarget.Match("m", 125)).startSeconds)

    @Test fun startSecondsNullForNonMatch() {
        allTargets.filter { it !is DeepLinkTarget.Match }.forEach {
            assertNull(DeepLinkRouter.plan(it).startSeconds)
        }
    }

    @Test fun routeForIsLastRoute() {
        assertEquals("match/x", DeepLinkRouter.routeFor(DeepLinkTarget.Match("x", 5)))
        assertEquals("club/c", DeepLinkRouter.routeFor(DeepLinkTarget.Club("c")))
        assertEquals("notfound/empty", DeepLinkRouter.routeFor(DeepLinkTarget.NotFound("empty")))
    }

    @Test fun backReturnsHomeThenLeavesApp() {
        allTargets.forEach {
            val s = BackStack(DeepLinkRouter.plan(it).routes)
            s.pop()
            assertEquals("home", s.top())
            assertFalse(s.isEmpty())
            assertEquals("home", s.pop())
            assertTrue(s.isEmpty())
            assertNull(s.pop())
        }
    }

    @Test fun isTopLevelHomeHelper() {
        assertTrue(DeepLinkRouter.isTopLevelHome(listOf("home")))
        assertFalse(DeepLinkRouter.isTopLevelHome(listOf("home", "club/c")))
        assertFalse(DeepLinkRouter.isTopLevelHome(emptyList()))
    }
}
