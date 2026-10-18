package com.nascon.league.data.repo

import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import com.nascon.league.domain.model.User
import java.util.UUID

/**
 * Guest sessions. A guest holds a token that starts with `guest_`; the server treats it as read-only and answers
 * every write with 403 GUEST_WRITE_FORBIDDEN. A guest looks like a Viewer to the UI, so no screen needs a null session.
 */
object GuestSession {
    const val PREFIX = "guest_"
    const val USER_ID = "guest"

    /** Seeded development token. Debug builds only: it is never used on a release code path. */
    private const val DEBUG_DEV_TOKEN = "guest_anon_tok_7721"

    /** Used until the backend exposes a guest-session route: debug builds use the seeded dev token, release builds a random one. */
    fun newToken(debugBuild: Boolean, random: () -> String = { UUID.randomUUID().toString().replace("-", "") }): String =
        if (debugBuild) DEBUG_DEV_TOKEN else PREFIX + random()

    fun isGuestToken(token: String?): Boolean = token != null && token.startsWith(PREFIX)

    fun isGuest(session: Session?): Boolean = session?.user?.userId == USER_ID

    fun session(): Session = Session(User(userId = USER_ID, email = "", role = Role.VIEWER))
}
