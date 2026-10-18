package com.nascon.league.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.data.orders.ClubOrderDto
import com.nascon.league.data.orders.OrderStatusRequest
import com.nascon.league.data.orders.clubOrdersApi
import com.nascon.league.di.AppContainer
import com.nascon.league.ui.components.isFeatureUnavailable
import com.nascon.league.ui.failure.userMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ClubOrdersState(
    val loading: Boolean = true,
    val orders: List<ClubOrderDto>? = null,
    val errorMessage: String? = null,
    val unavailable: Boolean = false,
)

class ClubOrdersViewModel(private val container: AppContainer, private val clubId: String) : ViewModel() {
    private val api by lazy { container.clubOrdersApi() }
    private val _state = MutableStateFlow(ClubOrdersState())
    val state: StateFlow<ClubOrdersState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, errorMessage = null, unavailable = false) }
        viewModelScope.launch {
            when (val r = safeCall { api.clubOrders(clubId) }) {
                is NetworkResult.Success -> _state.value =
                    ClubOrdersState(loading = false, orders = sortClubOrdersNewestFirst(r.data))
                is NetworkResult.Failure -> _state.update {
                    it.copy(
                        loading = false,
                        unavailable = r.error.isFeatureUnavailable(),
                        errorMessage = if (r.error.isFeatureUnavailable()) null else userMessage(r.error),
                    )
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, clubId: String) = viewModelFactory {
            initializer { ClubOrdersViewModel(container, clubId) }
        }
    }
}

data class OrderActionState(
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val illegal: Boolean = false,
)

enum class OrderActionResult { UPDATED, REFUNDED }

/** Status changes and refunds for one order (real endpoints). Online only; nothing is queued. */
class OrderActionsViewModel(private val container: AppContainer, private val orderId: String) : ViewModel() {
    private val api by lazy { container.clubOrdersApi() }
    private val _state = MutableStateFlow(OrderActionState())
    val state: StateFlow<OrderActionState> = _state.asStateFlow()
    private val _done = Channel<OrderActionResult>(Channel.BUFFERED)
    val done = _done.receiveAsFlow()

    fun changeStatus(to: String) = run(OrderActionResult.UPDATED) { api.setStatus(orderId, OrderStatusRequest(to)) }

    fun refund() = run(OrderActionResult.REFUNDED) { api.refund(orderId) }

    fun dismissError() = _state.update { it.copy(errorMessage = null, illegal = false) }

    private fun run(result: OrderActionResult, call: suspend () -> Any) {
        if (_state.value.busy) return
        _state.value = OrderActionState(busy = true)
        viewModelScope.launch {
            when (val r = safeCall { call() }) {
                is NetworkResult.Success -> {
                    container.orderRepository.refresh(orderId)
                    _state.value = OrderActionState()
                    _done.send(result)
                }
                is NetworkResult.Failure -> _state.value = OrderActionState(
                    errorMessage = userMessage(r.error),
                    illegal = (r.error as? ApiError.Http)?.code == "ILLEGAL_TRANSITION",
                )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, orderId: String) = viewModelFactory {
            initializer { OrderActionsViewModel(container, orderId) }
        }
    }
}
