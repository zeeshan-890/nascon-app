package com.nascon.league.data.repo

import com.nascon.league.BuildConfig
import com.nascon.league.core.security.TokenStore
import com.nascon.league.domain.model.Session
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SessionEvent {
    data object Expired : SessionEvent
}

class SessionRepository(
    private val tokenStore: TokenStore,
    private val debugBuild: Boolean = BuildConfig.DEBUG,
) {
    private val _session = MutableStateFlow(tokenStore.loadSession())
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    fun setSession(session: Session) {
        tokenStore.saveSession(session)
        _session.value = session
    }

    /** A league the signed-in organizer just created counts as theirs at once, without waiting for a new sign-in. */
    fun addLeague(leagueId: String) {
        val cur = _session.value ?: return
        if (leagueId in cur.user.leagueIds) return
        setSession(cur.copy(user = cur.user.copy(leagueIds = cur.user.leagueIds + leagueId)))
    }

    fun clear() {
        tokenStore.clear()
        _session.value = null
    }

    /** App start: a stored member session wins; otherwise the user is a guest. */
    fun ensureSession() {
        if (_session.value == null) startGuest()
    }

    /** Drops any member tokens and becomes a read-only guest. Also the target state of sign-out and a rejected token. */
    fun startGuest() {
        tokenStore.clear()
        tokenStore.saveTokens(GuestSession.newToken(debugBuild), "", GUEST_EXPIRES_AT_EPOCH)
        setSession(GuestSession.session())
    }

    val isGuest: Boolean get() = GuestSession.isGuest(_session.value)

    /** A rejected member token drops to Guest and tells the UI. A guest token being rejected changes nothing. */
    fun expire() {
        val cur = _session.value ?: return
        if (GuestSession.isGuest(cur)) return
        startGuest()
        _events.tryEmit(SessionEvent.Expired)
    }

    private companion object {
        /** Guest tokens do not expire on the client (year 2100, epoch seconds). */
        const val GUEST_EXPIRES_AT_EPOCH = 4_102_444_800L
    }
}
