package com.nascon.league.ui.useradmin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.data.useradmin.AdminUserDto
import com.nascon.league.data.useradmin.AssignmentOptionsResponse
import com.nascon.league.data.useradmin.ChangeRoleRequest
import com.nascon.league.data.useradmin.NewUserRequest
import com.nascon.league.data.useradmin.UserAdminEvents
import com.nascon.league.data.useradmin.UserFormError
import com.nascon.league.data.useradmin.canDeactivate
import com.nascon.league.data.useradmin.displayName
import com.nascon.league.data.useradmin.roleEnum
import com.nascon.league.data.useradmin.roleRequiresClub
import com.nascon.league.data.useradmin.roleRequiresLeague
import com.nascon.league.data.useradmin.userAdminApi
import com.nascon.league.data.useradmin.validateAssignment
import com.nascon.league.data.useradmin.validateNewUser
import com.nascon.league.di.AppContainer
import com.nascon.league.domain.model.Role
import com.nascon.league.ui.components.isFeatureUnavailable
import com.nascon.league.ui.failure.userMessage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Load state shared by the user screens. */
sealed interface UaLoad<out T> {
    data object Loading : UaLoad<Nothing>
    data class Data<T>(val value: T) : UaLoad<T>
    /** [unavailable]: the server has no such endpoint. [notFound]: the user no longer exists. */
    data class Failed(val message: String, val unavailable: Boolean = false, val notFound: Boolean = false) : UaLoad<Nothing>
}

private fun failed(error: ApiError): UaLoad.Failed {
    val http = error as? ApiError.Http
    return UaLoad.Failed(
        message = userMessage(error),
        unavailable = error.isFeatureUnavailable(),
        notFound = http?.status == 404 && http.code == "USER_NOT_FOUND",
    )
}

private fun currentUserId(container: AppContainer): String? =
    container.sessionRepository.session.value?.user?.userId

// ---------------------------------------------------------------- Admin home

class AdminHomeViewModel(container: AppContainer) : ViewModel() {
    private val _userCount = MutableStateFlow<Int?>(null)
    /** Real total from the users list; null until known or when the server cannot say. */
    val userCount: StateFlow<Int?> = _userCount.asStateFlow()

    init {
        viewModelScope.launch {
            UserAdminEvents.version.collectLatest {
                val r = safeCall { container.userAdminApi().users() }
                if (r is NetworkResult.Success) _userCount.value = r.data.total
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { AdminHomeViewModel(container) } }
    }
}

// ---------------------------------------------------------------- Users list

data class UsersListUiState(
    val query: String = "",
    /** Raw API role string, or null for "All". */
    val role: String? = null,
    val load: UaLoad<List<AdminUserDto>> = UaLoad.Loading,
)

@OptIn(FlowPreview::class)
class UsersListViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(UsersListUiState())
    val state: StateFlow<UsersListUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                _state.map { it.query.trim() to it.role }.distinctUntilChanged(),
                UserAdminEvents.version,
            ) { filters, _ -> filters }
                .debounce(250)
                .collectLatest { (q, role) -> fetch(q, role) }
        }
    }

    fun onQuery(v: String) = _state.update { it.copy(query = v) }
    fun onRole(v: String?) = _state.update { it.copy(role = v) }
    fun retry() {
        val s = _state.value
        viewModelScope.launch { fetch(s.query.trim(), s.role) }
    }

    private suspend fun fetch(q: String, role: String?) {
        if (_state.value.load !is UaLoad.Data) _state.update { it.copy(load = UaLoad.Loading) }
        when (val r = safeCall { container.userAdminApi().users(q.ifEmpty { null }, role) }) {
            is NetworkResult.Success -> _state.update { it.copy(load = UaLoad.Data(r.data.items)) }
            is NetworkResult.Failure -> _state.update { it.copy(load = failed(r.error)) }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { UsersListViewModel(container) } }
    }
}

// ---------------------------------------------------------------- Detail

sealed interface UserDetailEvent {
    data class Deactivated(val name: String) : UserDetailEvent
    data class Reactivated(val name: String) : UserDetailEvent
}

data class UserDetailUiState(
    val load: UaLoad<AdminUserDto> = UaLoad.Loading,
    val confirmDeactivate: Boolean = false,
    val working: Boolean = false,
    /** Set when the admin tries to change their own account. */
    val selfBlocked: Boolean = false,
    val actionError: String? = null,
)

class UserDetailViewModel(private val container: AppContainer, private val userId: String) : ViewModel() {
    private val _state = MutableStateFlow(UserDetailUiState())
    val state: StateFlow<UserDetailUiState> = _state.asStateFlow()
    private val _events = Channel<UserDetailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val isSelf: Boolean get() = !canDeactivate(currentUserId(container), userId)

    init {
        viewModelScope.launch { UserAdminEvents.version.collectLatest { load() } }
    }

    fun load() {
        viewModelScope.launch {
            if (_state.value.load !is UaLoad.Data) _state.update { it.copy(load = UaLoad.Loading) }
            when (val r = safeCall { container.userAdminApi().user(userId) }) {
                is NetworkResult.Success -> _state.update { it.copy(load = UaLoad.Data(r.data)) }
                is NetworkResult.Failure -> _state.update { it.copy(load = failed(r.error)) }
            }
        }
    }

    fun askDeactivate() {
        if (isSelf) _state.update { it.copy(selfBlocked = true) }
        else _state.update { it.copy(confirmDeactivate = true, actionError = null) }
    }

    fun dismissDialog() = _state.update { it.copy(confirmDeactivate = false) }

    fun confirmDeactivate() {
        if (isSelf) {
            _state.update { it.copy(confirmDeactivate = false, selfBlocked = true) }
            return
        }
        setActive(false)
    }

    fun reactivate() = setActive(true)

    private fun setActive(active: Boolean) {
        if (_state.value.working) return
        _state.update { it.copy(working = true, confirmDeactivate = false, actionError = null) }
        viewModelScope.launch {
            val r = safeCall {
                if (active) container.userAdminApi().activate(userId) else container.userAdminApi().deactivate(userId)
            }
            when (r) {
                is NetworkResult.Success -> {
                    _state.update { it.copy(working = false, load = UaLoad.Data(r.data)) }
                    UserAdminEvents.changed()
                    _events.send(if (active) UserDetailEvent.Reactivated(r.data.displayName()) else UserDetailEvent.Deactivated(r.data.displayName()))
                }
                is NetworkResult.Failure -> _state.update { it.copy(working = false, actionError = userMessage(r.error)) }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, userId: String) =
            viewModelFactory { initializer { UserDetailViewModel(container, userId) } }
    }
}

// ---------------------------------------------------------------- Assignment helpers

/** Club/league choices; null while loading or when they could not be loaded. */
private suspend fun loadOptions(container: AppContainer): NetworkResult<AssignmentOptionsResponse> =
    safeCall { container.userAdminApi().assignmentOptions() }

// ---------------------------------------------------------------- Change role

data class UserRoleUiState(
    val load: UaLoad<AdminUserDto> = UaLoad.Loading,
    val options: AssignmentOptionsResponse = AssignmentOptionsResponse(),
    val role: String = Role.VIEWER.apiValue,
    val clubId: String? = null,
    val leagueId: String? = null,
    val saving: Boolean = false,
    val error: UserFormError? = null,
    val message: String? = null,
    val selfBlocked: Boolean = false,
)

sealed interface UserRoleEvent {
    data class Saved(val role: Role) : UserRoleEvent
}

class UserRoleViewModel(private val container: AppContainer, private val userId: String) : ViewModel() {
    private val _state = MutableStateFlow(UserRoleUiState())
    val state: StateFlow<UserRoleUiState> = _state.asStateFlow()
    private val _events = Channel<UserRoleEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        _state.update { it.copy(selfBlocked = !canDeactivate(currentUserId(container), userId)) }
        load()
    }

    fun load() {
        _state.update { it.copy(load = UaLoad.Loading) }
        viewModelScope.launch {
            when (val r = safeCall { container.userAdminApi().user(userId) }) {
                is NetworkResult.Success -> {
                    val opts = (loadOptions(container) as? NetworkResult.Success)?.data ?: AssignmentOptionsResponse()
                    _state.update {
                        it.copy(
                            load = UaLoad.Data(r.data), options = opts, role = r.data.role,
                            clubId = r.data.clubId, leagueId = r.data.leagueIds.firstOrNull(),
                        )
                    }
                }
                is NetworkResult.Failure -> _state.update { it.copy(load = failed(r.error)) }
            }
        }
    }

    fun selectRole(role: String) = _state.update { it.copy(role = role, error = null, message = null) }
    fun selectClub(id: String) = _state.update { it.copy(clubId = id, error = null) }
    fun selectLeague(id: String) = _state.update { it.copy(leagueId = id, error = null) }

    fun save() {
        val s = _state.value
        if (s.saving || s.selfBlocked) return
        val leagues = listOfNotNull(s.leagueId.takeIf { roleRequiresLeague(Role.fromApi(s.role)) })
        val club = s.clubId.takeIf { Role.fromApi(s.role).let { r -> roleRequiresClub(r) || r == Role.PLAYER } }
        val err = validateAssignment(s.role, club, leagues)
        if (err != null) {
            _state.update { it.copy(error = err) }
            return
        }
        _state.update { it.copy(saving = true, message = null, error = null) }
        viewModelScope.launch {
            when (val r = safeCall { container.userAdminApi().changeRole(userId, ChangeRoleRequest(s.role, club, leagues)) }) {
                is NetworkResult.Success -> {
                    _state.update { it.copy(saving = false, load = UaLoad.Data(r.data)) }
                    UserAdminEvents.changed()
                    _events.send(UserRoleEvent.Saved(r.data.roleEnum()))
                }
                is NetworkResult.Failure -> _state.update { it.copy(saving = false, message = userMessage(r.error)) }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, userId: String) =
            viewModelFactory { initializer { UserRoleViewModel(container, userId) } }
    }
}

// ---------------------------------------------------------------- New user

data class NewUserUiState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val role: String = Role.PLAYER.apiValue,
    val clubId: String? = null,
    val leagueId: String? = null,
    val errors: Map<String, UserFormError> = emptyMap(),
    val options: AssignmentOptionsResponse = AssignmentOptionsResponse(),
    /** Failure to reach the user endpoints at all (shown instead of the form). */
    val blocked: UaLoad.Failed? = null,
    val saving: Boolean = false,
    val message: String? = null,
)

sealed interface NewUserEvent {
    data class Created(val name: String) : NewUserEvent
}

class NewUserViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(NewUserUiState())
    val state: StateFlow<NewUserUiState> = _state.asStateFlow()
    private val _events = Channel<NewUserEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            when (val r = loadOptions(container)) {
                is NetworkResult.Success -> _state.update { it.copy(options = r.data) }
                is NetworkResult.Failure -> {
                    val f = failed(r.error)
                    if (f.unavailable) _state.update { it.copy(blocked = f) }
                    else _state.update { it.copy(message = f.message) }
                }
            }
        }
    }

    private fun edit(block: NewUserUiState.() -> NewUserUiState) = _state.update { it.block().copy(message = null) }

    fun onName(v: String) = edit { copy(name = v, errors = errors - "name") }
    fun onEmail(v: String) = edit { copy(email = v, errors = errors - "email") }
    fun onPassword(v: String) = edit { copy(password = v, errors = errors - "password") }
    fun togglePassword() = edit { copy(passwordVisible = !passwordVisible) }
    fun onRole(v: String) = edit { copy(role = v, errors = errors - "role" - "club" - "league") }
    fun onClub(v: String) = edit { copy(clubId = v, errors = errors - "club") }
    fun onLeague(v: String) = edit { copy(leagueId = v, errors = errors - "league") }

    fun submit() {
        val s = _state.value
        if (s.saving) return
        val r = Role.fromApi(s.role)
        val club = s.clubId.takeIf { roleRequiresClub(r) || r == Role.PLAYER }
        val leagues = listOfNotNull(s.leagueId.takeIf { roleRequiresLeague(r) })
        val errors = validateNewUser(s.name, s.email, s.password, s.role, club, leagues)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            val body = NewUserRequest(s.name.trim(), s.email.trim(), s.password, s.role, club, leagues)
            when (val res = safeCall { container.userAdminApi().createUser(body) }) {
                is NetworkResult.Success -> {
                    _state.update { it.copy(saving = false) }
                    UserAdminEvents.changed()
                    _events.send(NewUserEvent.Created(res.data.displayName()))
                }
                is NetworkResult.Failure -> {
                    val http = res.error as? ApiError.Http
                    if (http?.status == 409 && http.code == "EMAIL_TAKEN") {
                        _state.update { it.copy(saving = false, errors = it.errors + ("email" to UserFormError.EMAIL_TAKEN)) }
                    } else {
                        val f = failed(res.error)
                        _state.update { it.copy(saving = false, message = f.message, blocked = if (f.unavailable) f else null) }
                    }
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { NewUserViewModel(container) } }
    }
}
