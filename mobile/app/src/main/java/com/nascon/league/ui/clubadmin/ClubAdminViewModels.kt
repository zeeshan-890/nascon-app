package com.nascon.league.ui.clubadmin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.data.api.dto.StaffDto
import com.nascon.league.data.clubadmin.AdminProductDto
import com.nascon.league.data.clubadmin.ClubAdminApi
import com.nascon.league.data.clubadmin.ClubUpdateRequest
import com.nascon.league.data.clubadmin.CoordinateErrors
import com.nascon.league.data.clubadmin.FormError
import com.nascon.league.data.clubadmin.MAX_STAFF
import com.nascon.league.data.clubadmin.MAX_STOCK
import com.nascon.league.data.clubadmin.MAX_VARIANTS
import com.nascon.league.data.clubadmin.ProductWriteRequest
import com.nascon.league.data.clubadmin.SocialRow
import com.nascon.league.data.clubadmin.StaffListDto
import com.nascon.league.data.clubadmin.StockUpdateRequest
import com.nascon.league.data.clubadmin.VariantErrors
import com.nascon.league.data.clubadmin.VariantForm
import com.nascon.league.data.clubadmin.buildVenue
import com.nascon.league.data.clubadmin.clubAdminApi
import com.nascon.league.data.clubadmin.formatCoordinate
import com.nascon.league.data.clubadmin.minorToInput
import com.nascon.league.data.clubadmin.parseStock
import com.nascon.league.data.clubadmin.socialRowsOf
import com.nascon.league.data.clubadmin.socialsToJson
import com.nascon.league.data.clubadmin.toVariantDtos
import com.nascon.league.data.clubadmin.validateCoordinates
import com.nascon.league.data.clubadmin.validateName
import com.nascon.league.data.clubadmin.validateOptionalUrl
import com.nascon.league.data.clubadmin.validateSocials
import com.nascon.league.data.clubadmin.validateStaff
import com.nascon.league.data.clubadmin.validateVariants
import com.nascon.league.di.AppContainer
import com.nascon.league.ui.components.isFeatureUnavailable
import com.nascon.league.ui.failure.userMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoadPhase { Loading, Ready, Error, Unavailable }

/** Shared failure -> UI mapping. */
private fun failureOf(error: ApiError): Pair<Boolean, String> = error.isFeatureUnavailable() to userMessage(error)

// ---------------------------------------------------------------- E17 edit club

data class EditClubState(
    val phase: LoadPhase = LoadPhase.Loading,
    val loadError: String? = null,
    val name: String = "",
    val logoUrl: String = "",
    val venueName: String = "",
    val lat: String = "",
    val lng: String = "",
    val socials: List<SocialRow> = emptyList(),
    val nameError: FormError? = null,
    val logoError: FormError? = null,
    val coordErrors: CoordinateErrors = CoordinateErrors(),
    val socialErrors: Map<Int, FormError> = emptyMap(),
    val saving: Boolean = false,
    val saveError: String? = null,
    val dirty: Boolean = false,
)

class EditClubViewModel(private val container: AppContainer, private val clubId: String) : ViewModel() {
    private val api: ClubAdminApi get() = container.clubAdminApi()
    private val _state = MutableStateFlow(EditClubState())
    val state: StateFlow<EditClubState> = _state.asStateFlow()
    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(phase = LoadPhase.Loading, loadError = null) }
        viewModelScope.launch {
            val repo = container.clubRepository
            var club = repo.observe(clubId).first()?.data
            if (club == null) {
                when (val r = repo.refresh(clubId)) {
                    is NetworkResult.Failure -> {
                        _state.update { it.copy(phase = LoadPhase.Error, loadError = userMessage(r.error)) }
                        return@launch
                    }
                    is NetworkResult.Success -> club = repo.observe(clubId).first()?.data
                }
            }
            if (club == null) {
                _state.update { it.copy(phase = LoadPhase.Error, loadError = userMessage(ApiError.Unexpected(IllegalStateException()))) }
                return@launch
            }
            val c: com.nascon.league.data.api.dto.ClubDto = club
            _state.update {
                it.copy(
                    phase = LoadPhase.Ready, name = c.name, logoUrl = c.logoUrl.orEmpty(),
                    venueName = c.venue?.name.orEmpty(),
                    lat = formatCoordinate(c.venue?.lat), lng = formatCoordinate(c.venue?.lng),
                    socials = socialRowsOf(c.socials), dirty = false,
                )
            }
        }
    }

    private fun edit(block: (EditClubState) -> EditClubState) = _state.update { block(it).copy(dirty = true, saveError = null) }

    fun onName(v: String) = edit { it.copy(name = v, nameError = null) }
    fun onLogo(v: String) = edit { it.copy(logoUrl = v, logoError = null) }
    fun onVenueName(v: String) = edit { it.copy(venueName = v) }
    fun onLat(v: String) = edit { it.copy(lat = v, coordErrors = it.coordErrors.copy(lat = null)) }
    fun onLng(v: String) = edit { it.copy(lng = v, coordErrors = it.coordErrors.copy(lng = null)) }
    fun onSocialKey(i: Int, v: String) = edit { s -> s.copy(socials = s.socials.mapIndexed { n, r -> if (n == i) r.copy(key = v) else r }, socialErrors = s.socialErrors - i) }
    fun onSocialValue(i: Int, v: String) = edit { s -> s.copy(socials = s.socials.mapIndexed { n, r -> if (n == i) r.copy(value = v) else r }, socialErrors = s.socialErrors - i) }
    fun addSocial() = edit { it.copy(socials = it.socials + SocialRow("", "")) }
    fun removeSocial(i: Int) = edit { s -> s.copy(socials = s.socials.filterIndexed { n, _ -> n != i }, socialErrors = emptyMap()) }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val nameError = validateName(s.name)
        val logoError = validateOptionalUrl(s.logoUrl)
        val coord = validateCoordinates(s.lat, s.lng)
        val socialErrors = validateSocials(s.socials)
        if (nameError != null || logoError != null || coord.any || socialErrors.isNotEmpty()) {
            _state.update { it.copy(nameError = nameError, logoError = logoError, coordErrors = coord, socialErrors = socialErrors) }
            return
        }
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val body = ClubUpdateRequest(
                name = s.name.trim(),
                logoUrl = s.logoUrl.trim().ifEmpty { null },
                venue = buildVenue(s.venueName, s.lat, s.lng),
                socials = socialsToJson(s.socials),
            )
            when (val r = safeCall { api.updateClub(clubId, body) }) {
                is NetworkResult.Success -> {
                    container.clubRepository.refresh(clubId)
                    _state.update { it.copy(saving = false, dirty = false) }
                    _saved.send(Unit)
                }
                is NetworkResult.Failure -> {
                    val (unavailable, msg) = failureOf(r.error)
                    _state.update {
                        if (unavailable) it.copy(saving = false, phase = LoadPhase.Unavailable)
                        else it.copy(saving = false, saveError = msg)
                    }
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, clubId: String) = viewModelFactory { initializer { EditClubViewModel(container, clubId) } }
    }
}

// ---------------------------------------------------------------- E18 staff

data class StaffRow(val key: Int, val name: String, val role: String)

data class EditStaffState(
    val phase: LoadPhase = LoadPhase.Loading,
    val loadError: String? = null,
    val rows: List<StaffRow> = emptyList(),
    val errors: Map<Int, FormError> = emptyMap(),
    val tooMany: Boolean = false,
    val saving: Boolean = false,
    val saveError: String? = null,
    val dirty: Boolean = false,
    val nextKey: Int = 0,
)

class EditStaffViewModel(private val container: AppContainer, private val clubId: String) : ViewModel() {
    private val api: ClubAdminApi get() = container.clubAdminApi()
    private val _state = MutableStateFlow(EditStaffState())
    val state: StateFlow<EditStaffState> = _state.asStateFlow()
    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(phase = LoadPhase.Loading, loadError = null) }
        viewModelScope.launch {
            val repo = container.clubRepository
            var club = repo.observe(clubId).first()?.data
            if (club == null) {
                when (val r = repo.refresh(clubId)) {
                    is NetworkResult.Failure -> {
                        _state.update { it.copy(phase = LoadPhase.Error, loadError = userMessage(r.error)) }
                        return@launch
                    }
                    is NetworkResult.Success -> club = repo.observe(clubId).first()?.data
                }
            }
            val staff = club?.staff.orEmpty()
            _state.update {
                it.copy(
                    phase = LoadPhase.Ready,
                    rows = staff.mapIndexed { i, s -> StaffRow(i, s.name, s.role) },
                    nextKey = staff.size, dirty = false,
                )
            }
        }
    }

    private fun edit(block: (EditStaffState) -> EditStaffState) = _state.update { block(it).copy(dirty = true, saveError = null) }

    fun onName(key: Int, v: String) = edit { s -> s.copy(rows = s.rows.map { if (it.key == key) it.copy(name = v) else it }, errors = emptyMap()) }
    fun onRole(key: Int, v: String) = edit { s -> s.copy(rows = s.rows.map { if (it.key == key) it.copy(role = v) else it }, errors = emptyMap()) }
    fun remove(key: Int) = edit { s -> s.copy(rows = s.rows.filterNot { it.key == key }, errors = emptyMap(), tooMany = false) }
    fun add() {
        if (_state.value.rows.size >= MAX_STAFF) {
            _state.update { it.copy(tooMany = true) }
            return
        }
        edit { s -> s.copy(rows = s.rows + StaffRow(s.nextKey, "", ""), nextKey = s.nextKey + 1) }
    }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val dtos = s.rows.map { StaffDto(it.name, it.role) }
        val errors = validateStaff(dtos)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors.mapKeys { (i, _) -> s.rows[i].key }) }
            return
        }
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            when (val r = safeCall { api.updateStaff(clubId, StaffListDto(dtos.map { StaffDto(it.name.trim(), it.role.trim()) })) }) {
                is NetworkResult.Success -> {
                    container.clubRepository.refresh(clubId)
                    _state.update { it.copy(saving = false, dirty = false) }
                    _saved.send(Unit)
                }
                is NetworkResult.Failure -> {
                    val (unavailable, msg) = failureOf(r.error)
                    _state.update {
                        if (unavailable) it.copy(saving = false, phase = LoadPhase.Unavailable)
                        else it.copy(saving = false, saveError = msg)
                    }
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, clubId: String) = viewModelFactory { initializer { EditStaffViewModel(container, clubId) } }
    }
}

// ---------------------------------------------------------------- E19 products list

data class ClubProductsState(
    val phase: LoadPhase = LoadPhase.Loading,
    val error: String? = null,
    val clubName: String = "",
    val products: List<AdminProductDto> = emptyList(),
    val refreshing: Boolean = false,
)

class ClubProductsViewModel(private val container: AppContainer, private val clubId: String) : ViewModel() {
    private val api: ClubAdminApi get() = container.clubAdminApi()
    private val _state = MutableStateFlow(ClubProductsState())
    val state: StateFlow<ClubProductsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.clubRepository.observe(clubId).first()?.let { c -> _state.update { it.copy(clubName = c.data.name) } }
        }
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true, phase = if (it.products.isEmpty() && it.phase != LoadPhase.Ready) LoadPhase.Loading else it.phase) }
        viewModelScope.launch {
            when (val r = safeCall { api.products(clubId) }) {
                is NetworkResult.Success -> _state.update { it.copy(phase = LoadPhase.Ready, products = r.data, refreshing = false, error = null) }
                is NetworkResult.Failure -> {
                    val (unavailable, msg) = failureOf(r.error)
                    _state.update {
                        when {
                            unavailable -> it.copy(phase = LoadPhase.Unavailable, refreshing = false)
                            it.products.isNotEmpty() -> it.copy(refreshing = false, error = msg)
                            else -> it.copy(phase = LoadPhase.Error, refreshing = false, error = msg)
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, clubId: String) = viewModelFactory { initializer { ClubProductsViewModel(container, clubId) } }
    }
}

// ---------------------------------------------------------------- E20 product edit / create

const val NEW_PRODUCT = "new"

data class ProductEditState(
    val phase: LoadPhase = LoadPhase.Loading,
    val loadError: String? = null,
    val isNew: Boolean = false,
    val name: String = "",
    val category: String = "",
    val imageUrl: String = "",
    val active: Boolean = true,
    val variants: List<VariantForm> = emptyList(),
    val nextKey: Int = 0,
    val nameError: FormError? = null,
    val imageError: FormError? = null,
    val variantErrors: Map<Int, VariantErrors> = emptyMap(),
    val noVariants: Boolean = false,
    val tooMany: Boolean = false,
    val saving: Boolean = false,
    val saveError: String? = null,
    val dirty: Boolean = false,
)

class ProductEditViewModel(
    private val container: AppContainer,
    private val clubId: String,
    private val productId: String,
) : ViewModel() {
    private val api: ClubAdminApi get() = container.clubAdminApi()
    private val isNew = productId == NEW_PRODUCT
    private val _state = MutableStateFlow(ProductEditState(isNew = isNew))
    val state: StateFlow<ProductEditState> = _state.asStateFlow()
    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()

    init { load() }

    fun load() {
        if (isNew) {
            _state.update { it.copy(phase = LoadPhase.Ready, variants = listOf(VariantForm(0, stock = "0")), nextKey = 1) }
            return
        }
        _state.update { it.copy(phase = LoadPhase.Loading, loadError = null) }
        viewModelScope.launch {
            when (val r = safeCall { api.products(clubId) }) {
                is NetworkResult.Success -> {
                    val p = r.data.firstOrNull { it.productId == productId }
                    if (p == null) {
                        _state.update { it.copy(phase = LoadPhase.Error, loadError = com.nascon.league.core.error.ErrorMapper.forCode("PRODUCT_NOT_FOUND")) }
                    } else {
                        _state.update {
                            it.copy(
                                phase = LoadPhase.Ready, name = p.name, category = p.category, imageUrl = p.imageUrl.orEmpty(),
                                active = p.active,
                                variants = p.variants.mapIndexed { i, v ->
                                    VariantForm(i, v.variantId, v.size.orEmpty(), v.color.orEmpty(), minorToInput(v.priceMinor), v.stock.toString())
                                },
                                nextKey = p.variants.size, dirty = false,
                            )
                        }
                    }
                }
                is NetworkResult.Failure -> {
                    val (unavailable, msg) = failureOf(r.error)
                    _state.update { if (unavailable) it.copy(phase = LoadPhase.Unavailable) else it.copy(phase = LoadPhase.Error, loadError = msg) }
                }
            }
        }
    }

    private fun edit(block: (ProductEditState) -> ProductEditState) = _state.update { block(it).copy(dirty = true, saveError = null) }
    private fun editVariant(key: Int, block: (VariantForm) -> VariantForm) =
        edit { s -> s.copy(variants = s.variants.map { if (it.key == key) block(it) else it }, variantErrors = s.variantErrors - s.variants.indexOfFirst { it.key == key }) }

    fun onName(v: String) = edit { it.copy(name = v, nameError = null) }
    fun onCategory(v: String) = edit { it.copy(category = v) }
    fun onImage(v: String) = edit { it.copy(imageUrl = v, imageError = null) }
    fun onActive(v: Boolean) = edit { it.copy(active = v) }
    fun onSize(key: Int, v: String) = editVariant(key) { it.copy(size = v) }
    fun onColor(key: Int, v: String) = editVariant(key) { it.copy(color = v) }
    fun onPrice(key: Int, v: String) = editVariant(key) { it.copy(price = v) }
    fun onStock(key: Int, v: String) = editVariant(key) { it.copy(stock = v) }
    fun removeVariant(key: Int) = edit { s -> s.copy(variants = s.variants.filterNot { it.key == key }, variantErrors = emptyMap(), tooMany = false) }
    fun addVariant() {
        if (_state.value.variants.size >= MAX_VARIANTS) {
            _state.update { it.copy(tooMany = true) }
            return
        }
        edit { s -> s.copy(variants = s.variants + VariantForm(s.nextKey, stock = "0"), nextKey = s.nextKey + 1, noVariants = false) }
    }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val nameError = validateName(s.name)
        val imageError = validateOptionalUrl(s.imageUrl)
        val vErrors = validateVariants(s.variants)
        val noVariants = s.variants.isEmpty()
        if (nameError != null || imageError != null || vErrors.isNotEmpty() || noVariants) {
            _state.update { it.copy(nameError = nameError, imageError = imageError, variantErrors = vErrors, noVariants = noVariants) }
            return
        }
        val body = ProductWriteRequest(
            name = s.name.trim(), category = s.category.trim(), imageUrl = s.imageUrl.trim().ifEmpty { null },
            active = s.active, variants = toVariantDtos(s.variants),
        )
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val r = if (isNew) safeCall { api.createProduct(clubId, body) } else safeCall { api.updateProduct(clubId, productId, body) }
            when (r) {
                is NetworkResult.Success -> {
                    _state.update { it.copy(saving = false, dirty = false) }
                    _saved.send(Unit)
                }
                is NetworkResult.Failure -> {
                    val (unavailable, msg) = failureOf(r.error)
                    _state.update {
                        if (unavailable) it.copy(saving = false, phase = LoadPhase.Unavailable)
                        else it.copy(saving = false, saveError = msg)
                    }
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, clubId: String, productId: String) =
            viewModelFactory { initializer { ProductEditViewModel(container, clubId, productId) } }
    }
}

// ---------------------------------------------------------------- E21 stock

data class StockRow(val variantId: String, val label: String, val original: Int, val text: String)

data class StockState(
    val phase: LoadPhase = LoadPhase.Loading,
    val loadError: String? = null,
    val productName: String = "",
    val rows: List<StockRow> = emptyList(),
    val invalid: Set<String> = emptySet(),
    val confirming: Boolean = false,
    val saving: Boolean = false,
    val saveError: String? = null,
) {
    val changed: List<StockRow> get() = rows.filter { parseStock(it.text) != null && parseStock(it.text) != it.original }
}

class StockViewModel(
    private val container: AppContainer,
    private val clubId: String,
    private val productId: String,
) : ViewModel() {
    private val api: ClubAdminApi get() = container.clubAdminApi()
    private val _state = MutableStateFlow(StockState())
    val state: StateFlow<StockState> = _state.asStateFlow()
    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(phase = LoadPhase.Loading, loadError = null) }
        viewModelScope.launch {
            when (val r = safeCall { api.products(clubId) }) {
                is NetworkResult.Success -> {
                    val p = r.data.firstOrNull { it.productId == productId }
                    if (p == null) {
                        _state.update { it.copy(phase = LoadPhase.Error, loadError = com.nascon.league.core.error.ErrorMapper.forCode("PRODUCT_NOT_FOUND")) }
                    } else {
                        _state.update {
                            it.copy(
                                phase = LoadPhase.Ready, productName = p.name,
                                rows = p.variants.mapNotNull { v ->
                                    v.variantId?.let { id ->
                                        StockRow(id, com.nascon.league.data.clubadmin.variantLabel(v.size, v.color), v.stock, v.stock.toString())
                                    }
                                },
                            )
                        }
                    }
                }
                is NetworkResult.Failure -> {
                    val (unavailable, msg) = failureOf(r.error)
                    _state.update { if (unavailable) it.copy(phase = LoadPhase.Unavailable) else it.copy(phase = LoadPhase.Error, loadError = msg) }
                }
            }
        }
    }

    private fun setText(id: String, text: String) =
        _state.update { s -> s.copy(rows = s.rows.map { if (it.variantId == id) it.copy(text = text) else it }, invalid = s.invalid - id, saveError = null) }

    fun onText(id: String, v: String) = setText(id, v.filter { it.isDigit() }.take(6))

    /** Steppers never go below zero or above the cap. */
    fun step(id: String, delta: Int) {
        val row = _state.value.rows.firstOrNull { it.variantId == id } ?: return
        val now = parseStock(row.text) ?: 0
        setText(id, (now + delta).coerceIn(0, MAX_STOCK).toString())
    }

    fun requestSave() {
        val s = _state.value
        if (s.saving) return
        val bad = s.rows.filter { parseStock(it.text) == null }.map { it.variantId }.toSet()
        if (bad.isNotEmpty()) {
            _state.update { it.copy(invalid = bad) }
            return
        }
        if (s.changed.isEmpty()) {
            viewModelScope.launch { _saved.send(Unit) }
            return
        }
        _state.update { it.copy(confirming = true) }
    }

    fun dismissConfirm() = _state.update { it.copy(confirming = false) }

    fun confirmSave() {
        val s = _state.value
        if (s.saving) return
        _state.update { it.copy(confirming = false, saving = true, saveError = null) }
        viewModelScope.launch {
            // Rows already saved are rebased so a retry only sends what is still pending.
            for (row in s.changed) {
                val stock = parseStock(row.text) ?: continue
                when (val r = safeCall { api.updateStock(clubId, productId, row.variantId, StockUpdateRequest(stock)) }) {
                    is NetworkResult.Success -> _state.update { st ->
                        st.copy(rows = st.rows.map { if (it.variantId == row.variantId) it.copy(original = stock) else it })
                    }
                    is NetworkResult.Failure -> {
                        val (unavailable, msg) = failureOf(r.error)
                        _state.update {
                            if (unavailable) it.copy(saving = false, phase = LoadPhase.Unavailable)
                            else it.copy(saving = false, saveError = msg)
                        }
                        return@launch
                    }
                }
            }
            _state.update { it.copy(saving = false) }
            _saved.send(Unit)
        }
    }

    companion object {
        fun factory(container: AppContainer, clubId: String, productId: String) =
            viewModelFactory { initializer { StockViewModel(container, clubId, productId) } }
    }
}
