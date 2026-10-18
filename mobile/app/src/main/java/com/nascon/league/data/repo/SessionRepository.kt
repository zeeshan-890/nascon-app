package com.nascon.league.data.repo

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

class SessionRepository(private val tokenStore: TokenStore) {
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

    fun expire() {
        if (_session.value == null) return
        clear()
        _events.tryEmit(SessionEvent.Expired)
    }
}
