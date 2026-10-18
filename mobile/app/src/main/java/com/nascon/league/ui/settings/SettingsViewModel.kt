package com.nascon.league.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.config.ServerConfigStore
import com.nascon.league.ui.failure.userMessage
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.di.AppContainer
import com.nascon.league.domain.model.Session
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TestState {
    data object Idle : TestState
    data object Loading : TestState
    data object Success : TestState
    data class Failed(val message: String) : TestState
}

data class SettingsUiState(
    val address: String = "",
    val addressError: String? = null,
    val test: TestState = TestState.Idle,
    val useDemo: Boolean = false,
    val deviceId: String = "",
    val loaded: Boolean = false,
)

private const val INVALID_URL = "Enter an address like http://192.168.1.20:8080"

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val config = container.configStore
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    val session: StateFlow<Session?> = container.sessionRepository.session

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _signedOut = Channel<Unit>(Channel.BUFFERED)
    val signedOut = _signedOut.receiveAsFlow()

    private val _cleared = Channel<Unit>(Channel.BUFFERED)
    val cleared = _cleared.receiveAsFlow()

    init {
        viewModelScope.launch {
            val url = config.baseUrl.first()
            val demo = config.useDemoData.first()
            val id = config.deviceId()
            _state.update { it.copy(address = url, useDemo = demo, deviceId = id, loaded = true) }
        }
    }

    fun onAddressChange(v: String) =
        _state.update { it.copy(address = v, addressError = null, test = TestState.Idle) }

    fun save() {
        val raw = _state.value.address
        if (ServerConfigStore.normalizeUrl(raw) == null) {
            _state.update { it.copy(addressError = INVALID_URL) }
            return
        }
        viewModelScope.launch {
            if (config.setBaseUrl(raw)) {
                val saved = config.baseUrl.first()
                _state.update { it.copy(address = saved, addressError = null) }
                _messages.send("Address saved")
            } else {
                _state.update { it.copy(addressError = INVALID_URL) }
            }
        }
    }

    fun testConnection() {
        if (_state.value.test == TestState.Loading) return
        val url = ServerConfigStore.normalizeUrl(_state.value.address)
        if (url == null) {
            _state.update { it.copy(addressError = INVALID_URL) }
            return
        }
        _state.update { it.copy(test = TestState.Loading, addressError = null) }
        viewModelScope.launch {
            val result = safeCall { container.apiFactory.create(url).ping() }
            val next = when (result) {
                is NetworkResult.Success -> TestState.Success
                is NetworkResult.Failure -> TestState.Failed(userMessage(result.error))
            }
            _state.update { it.copy(test = next) }
        }
    }

    fun setUseDemo(v: Boolean) {
        _state.update { it.copy(useDemo = v) }
        viewModelScope.launch { config.setUseDemoData(v) }
    }

    fun clearSavedData() {
        viewModelScope.launch {
            container.cacheManager.clearCache()
            _cleared.send(Unit)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            container.authRepository.logout()
            _signedOut.send(Unit)
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SettingsViewModel(container) }
        }
    }
}
