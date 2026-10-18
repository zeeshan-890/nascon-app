package com.nascon.league.ui.login

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.error.ErrorContext
import com.nascon.league.ui.failure.userMessage
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.data.fake.DemoAccount
import com.nascon.league.di.AppContainer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginField { EMAIL, PASSWORD }

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val loading: Boolean = false,
    val emailError: String? = null,
    val passwordError: String? = null,
    val errorMessage: String? = null,
    val serverUnreachable: Boolean = false,
    val demoExpanded: Boolean = true,
    val focusRequest: LoginField? = null,
    val failureKind: LoginFailureKind? = null,
    val attemptsLeft: Int? = null,
    /** Seconds the account is locked for, when the server said; null = locked without a known countdown. */
    val lockedForSeconds: Int? = null,
    /** Changes on each new lock so the countdown restarts. */
    val lockId: Int = 0,
)

/** Editing a field clears a stale credentials error but keeps an active lock message. */
private fun LoginUiState.clearedError(): LoginUiState =
    if (failureKind == LoginFailureKind.LOCKED && lockedForSeconds != null) this
    else copy(errorMessage = null, failureKind = null, attemptsLeft = null)

class LoginViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val _loggedIn = Channel<String>(Channel.BUFFERED)
    val loggedIn = _loggedIn.receiveAsFlow()

    fun onEmailChange(v: String) =
        _state.update { it.copy(email = v, emailError = null, serverUnreachable = false).clearedError() }

    fun onPasswordChange(v: String) =
        _state.update { it.copy(password = v, passwordError = null, serverUnreachable = false).clearedError() }

    fun lockExpired() = _state.update {
        if (it.failureKind == LoginFailureKind.LOCKED) it.copy(errorMessage = null, failureKind = null, lockedForSeconds = null) else it
    }

    fun togglePasswordVisible() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }
    fun toggleDemo() = _state.update { it.copy(demoExpanded = !it.demoExpanded) }
    fun focusHandled() = _state.update { it.copy(focusRequest = null) }

    fun fillDemo(account: DemoAccount) = _state.update {
        it.copy(
            email = account.email, password = account.password,
            emailError = null, passwordError = null, errorMessage = null, serverUnreachable = false,
        )
    }

    fun submit() {
        val s = _state.value
        if (s.loading) return
        val email = s.email.trim()
        val emailError = when {
            email.isEmpty() -> "Enter your email"
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> "Enter a valid email"
            else -> null
        }
        val passwordError = if (s.password.isEmpty()) "Enter your password" else null
        if (emailError != null || passwordError != null) {
            _state.update {
                it.copy(
                    emailError = emailError,
                    passwordError = passwordError,
                    focusRequest = if (emailError != null) LoginField.EMAIL else LoginField.PASSWORD,
                )
            }
            return
        }
        _state.update { it.copy(loading = true, errorMessage = null, serverUnreachable = false, failureKind = null, attemptsLeft = null, lockedForSeconds = null) }
        viewModelScope.launch {
            when (val r = container.authRepository.login(email, s.password)) {
                is NetworkResult.Success -> {
                    _state.update { it.copy(loading = false, password = "") }
                    _loggedIn.send(r.data.user.role.label)
                }
                is NetworkResult.Failure -> _state.update {
                    it.copy(
                        loading = false,
                        errorMessage = userMessage(r.error, ErrorContext.Login),
                        serverUnreachable = r.error is ApiError.ServerUnreachable,
                        failureKind = loginFailureKind(r.error),
                        attemptsLeft = attemptsLeft(r.error),
                        lockedForSeconds = lockedForSeconds(r.error),
                        lockId = it.lockId + 1,
                    )
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { LoginViewModel(container) }
        }
    }
}
