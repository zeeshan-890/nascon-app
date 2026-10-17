package com.nascon.league.ui.club

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.ConnState
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.data.api.dto.ClubDto
import com.nascon.league.data.api.dto.ClubSummaryDto
import com.nascon.league.data.repo.Cached
import com.nascon.league.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

data class ClubRefresh(val refreshing: Boolean = false, val error: ApiError? = null)

data class ClubProfileState(
    val cacheChecked: Boolean = false,
    val club: ClubDto? = null,
    val updatedAt: Instant? = null,
    val refresh: ClubRefresh = ClubRefresh(),
    val offline: Boolean = false,
)

class ClubProfileViewModel(
    private val container: AppContainer,
    val clubId: String,
) : ViewModel() {
    private val refreshInfo = MutableStateFlow(ClubRefresh(refreshing = true))
    private var inFlight = false

    /** Not-yet-decoded cache emits null both for "nothing saved" and "still reading". */
    private val cache = MutableStateFlow(false)

    val state: StateFlow<ClubProfileState> = combine(
        container.clubRepository.observe(clubId).map { it.also { cache.value = true } },
        refreshInfo,
        container.connectivity.state,
        cache,
    ) { cached: Cached<ClubDto>?, info, conn, checked ->
        ClubProfileState(
            cacheChecked = checked,
            club = cached?.data,
            updatedAt = cached?.updatedAt,
            refresh = info,
            offline = conn == ConnState.OFFLINE,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClubProfileState())

    init {
        refresh()
        viewModelScope.launch { container.refreshSignal.collect { refresh() } }
    }

    fun refresh() {
        if (inFlight) return
        if (container.connectivity.state.value == ConnState.OFFLINE) {
            refreshInfo.value = ClubRefresh(false, ApiError.NoConnection)
            return
        }
        inFlight = true
        refreshInfo.value = ClubRefresh(true, null)
        viewModelScope.launch {
            val r = container.clubRepository.refresh(clubId)
            inFlight = false
            refreshInfo.value = ClubRefresh(false, (r as? NetworkResult.Failure)?.error)
        }
    }

    companion object {
        fun factory(container: AppContainer, clubId: String) = viewModelFactory {
            initializer { ClubProfileViewModel(container, clubId) }
        }
    }
}

data class ClubsListState(
    val cacheChecked: Boolean = false,
    val clubs: List<ClubSummaryDto>? = null,
    val updatedAt: Instant? = null,
    val refresh: ClubRefresh = ClubRefresh(),
    val offline: Boolean = false,
)

class ClubsListViewModel(private val container: AppContainer) : ViewModel() {
    private val refreshInfo = MutableStateFlow(ClubRefresh(refreshing = true))
    private var inFlight = false
    private val checked = MutableStateFlow(false)
    val state: StateFlow<ClubsListState> = combine(
        container.clubsListRepository.observe().map { it.also { checked.value = true } },
        refreshInfo,
        container.connectivity.state,
        checked,
    ) { cached: Cached<List<ClubSummaryDto>>?, info, conn, isChecked ->
        ClubsListState(
            cacheChecked = isChecked,
            clubs = cached?.data,
            updatedAt = cached?.updatedAt,
            refresh = info,
            offline = conn == ConnState.OFFLINE,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClubsListState())

    init {
        refresh()
        viewModelScope.launch { container.refreshSignal.collect { refresh() } }
    }

    fun refresh() {
        if (inFlight) return
        if (container.connectivity.state.value == ConnState.OFFLINE) {
            refreshInfo.value = ClubRefresh(false, ApiError.NoConnection)
            return
        }
        inFlight = true
        refreshInfo.value = ClubRefresh(true, null)
        viewModelScope.launch {
            val r = container.clubsListRepository.refresh()
            inFlight = false
            refreshInfo.value = ClubRefresh(false, (r as? NetworkResult.Failure)?.error)
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ClubsListViewModel(container) }
        }
    }
}
