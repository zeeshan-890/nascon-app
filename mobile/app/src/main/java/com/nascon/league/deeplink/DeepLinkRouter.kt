package com.nascon.league.deeplink

/** Which match screen variant to show for a given match status. */
enum class MatchScreen { LIVE, PREVIEW, VOD, NOTICE, LIVE_SHELL }

/** Back stack (bottom to top) to build for a link. Always starts with home. */
data class NavPlan(val routes: List<String>, val startSeconds: Int?)

object DeepLinkRouter {
    fun matchScreenFor(status: String?): MatchScreen =
        when (status?.trim()?.lowercase()) {
            "in_progress" -> MatchScreen.LIVE
            "upcoming" -> MatchScreen.PREVIEW
            "completed" -> MatchScreen.VOD
            "postponed", "cancelled", "abandoned" -> MatchScreen.NOTICE
            else -> MatchScreen.LIVE_SHELL
        }

    fun plan(target: DeepLinkTarget): NavPlan {
        val top: String = when (target) {
            is DeepLinkTarget.Match -> DeepLinkRoutes.match(target.id)
            is DeepLinkTarget.Club -> DeepLinkRoutes.club(target.id)
            is DeepLinkTarget.Player -> DeepLinkRoutes.player(target.id)
            is DeepLinkTarget.CoStream -> DeepLinkRoutes.coStream(target.id)
            is DeepLinkTarget.NotFound -> DeepLinkRoutes.notFound(target.reason)
        }
        val routes = if (top == DeepLinkRoutes.HOME) listOf(top) else listOf(DeepLinkRoutes.HOME, top)
        val start = (target as? DeepLinkTarget.Match)?.startSeconds
        return NavPlan(routes, start)
    }

    fun routeFor(target: DeepLinkTarget): String = plan(target).routes.last()

    fun isTopLevelHome(routes: List<String>): Boolean =
        routes.size == 1 && routes.first() == DeepLinkRoutes.HOME
}
