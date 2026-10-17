package com.nascon.league.ui.store

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.core.network.ConnState
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.data.store.Cart
import com.nascon.league.data.store.CartLine
import com.nascon.league.data.store.CartProblem
import com.nascon.league.data.store.CheckoutAttempts
import com.nascon.league.data.store.CheckoutRequestBuilder
import com.nascon.league.data.store.PaymentMethod
import com.nascon.league.data.store.shouldClearAttempt
import com.nascon.league.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

data class CheckoutUiState(
    val lines: List<CartLine> = emptyList(),
    val subtotalMinor: Long = 0,
    val hasBlockingProblem: Boolean = false,
    val cartLoaded: Boolean = false,
    val online: Boolean = true,
    val discountCode: String = "",
    val method: PaymentMethod = PaymentMethod.APPROVED,
    val confirmOpen: Boolean = false,
    val inFlight: Boolean = false,
    val banner: BannerKind? = null,
    val bannerMessage: String? = null,
    val discountError: Boolean = false,
    /** Set once the order is accepted; the screen navigates to the success page. */
    val placedOrderId: String? = null,
    /** Set when the user must return to the cart (item sold out). */
    val backToCart: Boolean = false,
) {
    val hint: PayHint? get() = payHint(online, lines.size, hasBlockingProblem)
    val payEnabled: Boolean get() = payEnabled(online, inFlight, lines.size, hasBlockingProblem)
}

class CheckoutViewModel(
    private val container: AppContainer,
    private val handle: SavedStateHandle,
) : ViewModel() {
    /** Set synchronously on the first tap so double taps and rotation cannot start a second request. */
    private val guard = AtomicBoolean(false)

    private class Form(
        val discount: String,
        val method: PaymentMethod,
        val confirm: Boolean,
        val inFlight: Boolean,
        val banner: BannerKind?,
        val bannerMessage: String?,
        val discountError: Boolean,
        val placed: String?,
        val back: Boolean,
    )

    private val discount = MutableStateFlow(handle.get<String>("discount").orEmpty())
    private val method = MutableStateFlow(
        PaymentMethod.entries.firstOrNull { it.name == handle.get<String>("method") } ?: PaymentMethod.APPROVED,
    )
    private val confirm = MutableStateFlow(handle.get<Boolean>("confirm") ?: false)
    private val inFlight = MutableStateFlow(false)
    private val banner = MutableStateFlow<Pair<BannerKind?, String?>>(null to null)
    private val discountError = MutableStateFlow(false)
    private val placed = MutableStateFlow<String?>(null)
    private val back = MutableStateFlow(false)

    private val form = combine(
        combine(discount, method, confirm, inFlight) { d, m, c, f -> arrayOf<Any?>(d, m, c, f) },
        combine(banner, discountError, placed, back) { b, de, p, bk -> arrayOf<Any?>(b, de, p, bk) },
    ) { a, b ->
        @Suppress("UNCHECKED_CAST")
        val bn = b[0] as Pair<BannerKind?, String?>
        Form(
            a[0] as String, a[1] as PaymentMethod, a[2] as Boolean, a[3] as Boolean,
            bn.first, bn.second, b[1] as Boolean, b[2] as String?, b[3] as Boolean,
        )
    }

    val state: StateFlow<CheckoutUiState> = combine(
        container.cartStore.cart, form, container.connectivity.state,
    ) { cart: Cart, f, conn ->
        CheckoutUiState(
            lines = cart.lines,
            subtotalMinor = cart.subtotalMinor,
            hasBlockingProblem = cart.hasBlockingProblem,
            cartLoaded = true,
            online = conn != ConnState.OFFLINE,
            discountCode = f.discount,
            method = f.method,
            confirmOpen = f.confirm,
            inFlight = f.inFlight,
            banner = f.banner,
            bannerMessage = f.bannerMessage,
            discountError = f.discountError,
            placedOrderId = f.placed,
            backToCart = f.back,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CheckoutUiState())

    fun onDiscountChange(v: String) {
        discount.value = v
        handle["discount"] = v
        discountError.value = false
    }

    fun onMethodChange(m: PaymentMethod) {
        method.value = m
        handle["method"] = m.name
        banner.value = null to null
    }

    fun onPayTapped() {
        if (!state.value.payEnabled) return
        confirm.value = true
        handle["confirm"] = true
    }

    fun dismissConfirm() {
        confirm.value = false
        handle["confirm"] = false
    }

    fun backToCartHandled() {
        back.value = false
    }

    fun confirmPay() {
        dismissConfirm()
        if (!state.value.payEnabled) return
        if (!guard.compareAndSet(false, true)) return
        inFlight.value = true
        banner.value = null to null
        discountError.value = false
        viewModelScope.launch {
            try {
                pay()
            } finally {
                inFlight.value = false
                guard.set(false)
            }
        }
    }

    private suspend fun pay() {
        val lines = container.cartStore.cart.first().lines
        val userId = container.sessionRepository.session.value?.user?.userId
        if (lines.isEmpty() || userId == null ||
            lines.any { it.problem == CartProblem.NoLongerAvailable || it.problem == CartProblem.SoldOut }
        ) {
            return
        }
        val code = normalizeDiscount(discount.value)
        val token = method.value.token
        val hash = CheckoutAttempts.hash(lines.map { it.variantId to it.quantity }, code, token)
        // Same request => same order id (idempotent retry); anything changed => a fresh id.
        val attempt = CheckoutAttempts.resolve(container.checkoutAttemptStore.get(), hash)
        container.checkoutAttemptStore.set(attempt)
        val request = CheckoutRequestBuilder.build(attempt.orderId, userId, lines, code, token)

        when (val result = safeCall { container.apiProvider().checkout(request) }) {
            is NetworkResult.Success -> {
                val response = result.data
                val orderId = response.orderId.ifBlank { attempt.orderId }
                val pdf = response.receipt?.pdfBase64.orEmpty()
                if (pdf.isNotBlank()) {
                    // Saved immediately so the receipt exists even if the app is closed right now.
                    try {
                        container.receiptStore.saveReceipt(
                            orderId, pdf, response.totalMinor, Instant.now().toString(),
                        )
                    } catch (_: Exception) {
                        // The receipt can still be downloaded later from the server.
                    }
                }
                container.checkoutAttemptStore.clear()
                OrderResultHolder.put(orderId, response.totalMinor)
                container.cartStore.clear()
                placed.value = orderId
            }
            is NetworkResult.Failure -> {
                val r = resolveFailure(result.error, hasDiscount = code != null)
                if (shouldClearAttempt(r.outcome)) container.checkoutAttemptStore.clear()
                banner.value = r.banner to r.bannerMessage
                discountError.value = r.discountError
                if (r.backToCart) {
                    // Pull the latest stock, mark the line, then send the user back to the cart.
                    container.productRepository.refresh()
                    val marked = r.soldOutVariantId
                    if (marked != null) {
                        val current = container.cartStore.cart.first().lines
                        container.cartStore.replaceAll(
                            current.map { if (it.variantId == marked) it.copy(problem = CartProblem.SoldOut) else it },
                        )
                    }
                    CartNotice.post(ErrorMapper.forCode("OUT_OF_STOCK"))
                    back.value = true
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { CheckoutViewModel(container, createSavedStateHandle()) }
        }
    }
}
