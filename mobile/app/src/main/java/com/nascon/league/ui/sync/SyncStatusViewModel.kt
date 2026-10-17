package com.nascon.league.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.core.network.ConnState
import com.nascon.league.data.sync.SyncPhase
import com.nascon.league.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PendingItem(val actionId: String, val label: String, val queuedAtIso: String, val sending: Boolean)

data class ResultItem(
    val actionId: String,
    val label: String,
    val kind: ResultKind,
    /** Plain-language reason, only for rejected results. */
    val reason: String?,
    val syncedAt: Long,
)

data class SyncStatusUiState(
    val pending: List<PendingItem> = emptyList(),
    val results: List<ResultItem> = emptyList(),
    val counts: BatchCounts? = null,
    val online: Boolean = true,
    val syncing: Boolean = false,
    val needsLogin: Boolean = false,
    val lastError: String? = null,
)

class SyncStatusViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.syncRepository

    val state: StateFlow<SyncStatusUiState> = combine(
        repo.observePending(),
        repo.observeResults(),
        repo.state,
        container.connectivity.state,
    ) { pending, results, sync, conn ->
        val items = results.map {
            val kind = resultKind(it.status)
            ResultItem(
                actionId = it.actionId,
                label = it.label,
                kind = kind,
                reason = if (kind == ResultKind.REJECTED) ErrorMapper.syncRejection(it.reasonCode) else null,
                syncedAt = it.syncedAt,
            )
        }
        val batch = latestBatch(results) { it.syncedAt }
        SyncStatusUiState(
            pending = pending.map {
                PendingItem(it.actionId, it.label, it.queuedAt, sending = it.status == "SYNCING")
            },
            results = items,
            counts = if (batch.isEmpty()) null else countResults(batch.map { it.status }),
            online = conn != ConnState.OFFLINE,
            syncing = sync.phase == SyncPhase.SYNCING || conn == ConnState.SYNCING,
            needsLogin = sync.needsLogin,
            lastError = sync.lastError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatusUiState())

    fun syncNow() {
        viewModelScope.launch { repo.syncNow() }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SyncStatusViewModel(container) }
        }
    }
}
