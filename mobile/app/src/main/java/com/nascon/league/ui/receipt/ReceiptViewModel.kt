package com.nascon.league.ui.receipt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.data.receipts.ReceiptEntry
import com.nascon.league.data.receipts.ReceiptLoadResult
import com.nascon.league.data.receipts.ReceiptStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface ReceiptUiState {
    data object Loading : ReceiptUiState
    data object NotSavedOffline : ReceiptUiState
    data class Failed(val message: String) : ReceiptUiState
    data object Corrupt : ReceiptUiState
    data class Ready(val file: File, val pages: PdfPages) : ReceiptUiState
}

class ReceiptViewModel(
    private val store: ReceiptStore,
    private val orderId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<ReceiptUiState>(ReceiptUiState.Loading)
    val state: StateFlow<ReceiptUiState> = _state.asStateFlow()

    private val _entry = MutableStateFlow<ReceiptEntry?>(null)
    val entry: StateFlow<ReceiptEntry?> = _entry.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            store.observeIndex().collect { list -> _entry.value = list.firstOrNull { it.orderId == orderId } }
        }
        load()
    }

    fun load() {
        job?.cancel()
        closePages()
        _state.value = ReceiptUiState.Loading
        job = viewModelScope.launch {
            _state.value = when (val result = store.ensureDownloaded(orderId)) {
                is ReceiptLoadResult.Ready -> {
                    val pages = PdfPages.open(result.file)
                    if (pages == null) ReceiptUiState.Corrupt else ReceiptUiState.Ready(result.file, pages)
                }
                ReceiptLoadResult.NotSavedOffline -> ReceiptUiState.NotSavedOffline
                is ReceiptLoadResult.Failed -> ReceiptUiState.Failed(ErrorMapper.message(result.error))
            }
        }
    }

    /** Deletes the local copy and downloads it again. */
    fun downloadAgain() {
        job?.cancel()
        closePages()
        _state.value = ReceiptUiState.Loading
        job = viewModelScope.launch {
            store.delete(orderId)
            load()
        }
    }

    private fun closePages() {
        (_state.value as? ReceiptUiState.Ready)?.pages?.close()
    }

    override fun onCleared() {
        closePages()
        super.onCleared()
    }

    companion object {
        fun factory(store: ReceiptStore, orderId: String) = viewModelFactory {
            initializer { ReceiptViewModel(store, orderId) }
        }
    }
}
