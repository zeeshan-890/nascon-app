package com.nascon.league.ui.store

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.ConnState
import com.nascon.league.data.api.dto.ProductDto
import com.nascon.league.data.api.dto.VariantDto
import com.nascon.league.data.repo.Resource
import com.nascon.league.data.store.CartLine
import com.nascon.league.data.store.StoreLogic
import com.nascon.league.data.store.reconcile
import com.nascon.league.di.AppContainer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/** Catalogue snapshot shared by the store, product and cart view models. */
data class CatalogueState(
    /** null = nothing cached yet. */
    val products: List<ProductDto>? = null,
    val refreshing: Boolean = true,
    val error: ApiError? = null,
    val updatedAt: Instant? = null,
)

internal fun Resource<List<ProductDto>>.toCatalogue(): CatalogueState = when (this) {
    is Resource.Loading -> CatalogueState(cached, refreshing = true)
    is Resource.Success -> CatalogueState(data, refreshing = false, updatedAt = lastUpdatedAt)
    is Resource.Error -> CatalogueState(cached, refreshing = false, error = error)
}

/** Keeps the catalogue fresh: on start and whenever the connection returns. */
internal fun ViewModel.refreshCatalogueOnSignals(container: AppContainer) {
    viewModelScope.launch { container.productRepository.refresh() }
    viewModelScope.launch {
        container.refreshSignal.collect { container.productRepository.refresh() }
    }
}

// ---------------------------------------------------------------- Store list

data class StoreUiState(
    val catalogue: CatalogueState = CatalogueState(),
    /** Lower-cased category key, null = All. */
    val category: String? = null,
    val cartCount: Int = 0,
    val offline: Boolean = false,
) {
    val categories: List<String> get() = catalogue.products?.let(::categoryKeys).orEmpty()
    val visible: List<ProductDto>
        get() = filterByCategory(catalogue.products.orEmpty(), category)
}

class StoreViewModel(
    private val container: AppContainer,
    private val handle: SavedStateHandle,
) : ViewModel() {
    private val category = MutableStateFlow(handle.get<String>("category"))

    val state: StateFlow<StoreUiState> = combine(
        container.productRepository.observeResource().map { it.toCatalogue() },
        category,
        container.cartStore.cart.map { it.itemCount }.distinctUntilChanged(),
        container.connectivity.state,
    ) { cat, selected, count, conn ->
        // A category that vanished from fresh data falls back to All.
        val valid = selected?.takeIf { s -> cat.products?.let { s in categoryKeys(it) } ?: true }
        StoreUiState(cat, valid, count, conn == ConnState.OFFLINE)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StoreUiState())

    init {
        refreshCatalogueOnSignals(container)
    }

    fun selectCategory(key: String?) {
        category.value = key
        handle["category"] = key
    }

    fun refresh() {
        viewModelScope.launch { container.productRepository.refresh() }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { StoreViewModel(container, createSavedStateHandle()) }
        }
    }
}

// ------------------------------------------------------------ Product detail

data class ProductDetailUiState(
    val catalogue: CatalogueState = CatalogueState(),
    val product: ProductDto? = null,
    val variant: VariantDto? = null,
    val quantity: Int = 1,
) {
    val notFound: Boolean get() = catalogue.products != null && product == null
}

class ProductDetailViewModel(
    private val container: AppContainer,
    private val handle: SavedStateHandle,
    val productId: String,
) : ViewModel() {
    private val selectedVariant = MutableStateFlow(handle.get<String>("variant"))
    private val quantity = MutableStateFlow(handle.get<Int>("qty") ?: 1)
    private val _added = Channel<Unit>(Channel.BUFFERED)
    val added = _added.receiveAsFlow()

    val state: StateFlow<ProductDetailUiState> = combine(
        container.productRepository.observeResource().map { it.toCatalogue() },
        selectedVariant,
        quantity,
    ) { cat, sel, qty ->
        val product = cat.products?.firstOrNull { it.productId == productId }
        val variant = product?.let { chooseVariant(it, sel) }
        ProductDetailUiState(cat, product, variant, clampQuantity(qty, variant?.stock ?: 1))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProductDetailUiState())

    init {
        refreshCatalogueOnSignals(container)
    }

    fun selectVariant(variantId: String) {
        selectedVariant.value = variantId
        handle["variant"] = variantId
        setQuantity(1)
    }

    fun setQuantity(q: Int) {
        quantity.value = q
        handle["qty"] = q
    }

    fun refresh() {
        viewModelScope.launch { container.productRepository.refresh() }
    }

    fun addToCart() {
        val s = state.value
        val product = s.product ?: return
        val variant = s.variant ?: return
        viewModelScope.launch {
            container.cartStore.add(
                CartLine(
                    variantId = variant.variantId,
                    productId = product.productId,
                    name = product.name,
                    variantLabel = StoreLogic.variantLabel(variant.size, variant.color),
                    unitPriceMinor = variant.priceMinor,
                    quantity = s.quantity,
                    imageUrl = product.imageUrl,
                ),
            )
            _added.send(Unit)
        }
    }

    companion object {
        fun factory(container: AppContainer, productId: String) = viewModelFactory {
            initializer { ProductDetailViewModel(container, createSavedStateHandle(), productId) }
        }
    }
}

// ---------------------------------------------------------------------- Cart

data class CartUiState(
    val lines: List<CartLine> = emptyList(),
    val subtotalMinor: Long = 0,
    val hasBlockingProblem: Boolean = false,
    val stockByVariant: Map<String, Int> = emptyMap(),
    val loaded: Boolean = false,
)

class CartViewModel(private val container: AppContainer) : ViewModel() {
    private val _removed = Channel<CartLine>(Channel.BUFFERED)
    val removed = _removed.receiveAsFlow()

    private val products = container.productRepository.observeResource().map { it.toCatalogue().products }

    val state: StateFlow<CartUiState> = combine(container.cartStore.cart, products) { cart, prods ->
        val stock = prods.orEmpty().flatMap { p -> p.variants.map { it.variantId to it.stock } }.toMap()
        CartUiState(cart.lines, cart.subtotalMinor, cart.hasBlockingProblem, stock, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CartUiState())

    init {
        refreshCatalogueOnSignals(container)
        // Reconcile when the cart opens and after each products refresh (not on every cart edit,
        // so the "Price updated" chips stay visible until the next refresh).
        viewModelScope.launch {
            products.distinctUntilChanged().collect { prods ->
                if (prods == null) return@collect
                val lines = container.cartStore.cart.first().lines
                if (lines.isEmpty()) return@collect
                val next = reconcile(lines, prods)
                if (next != lines) container.cartStore.replaceAll(next)
            }
        }
    }

    fun setQuantity(variantId: String, qty: Int) {
        viewModelScope.launch { container.cartStore.setQuantity(variantId, qty) }
    }

    fun remove(variantId: String) {
        viewModelScope.launch {
            container.cartStore.remove(variantId)?.let { _removed.send(it) }
        }
    }

    fun undo(line: CartLine) {
        viewModelScope.launch { container.cartStore.restore(line) }
    }

    fun refresh() {
        viewModelScope.launch { container.productRepository.refresh() }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { CartViewModel(container) }
        }
    }
}
