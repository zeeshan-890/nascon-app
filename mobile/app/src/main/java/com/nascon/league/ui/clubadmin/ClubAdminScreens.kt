package com.nascon.league.ui.clubadmin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nascon.league.R
import com.nascon.league.data.clubadmin.AdminProductDto
import com.nascon.league.data.clubadmin.FormError
import com.nascon.league.data.clubadmin.MAX_STOCK
import com.nascon.league.data.clubadmin.ROLE_SUGGESTIONS
import com.nascon.league.data.clubadmin.StockKind
import com.nascon.league.data.clubadmin.parseStock
import com.nascon.league.data.clubadmin.priceRange
import com.nascon.league.data.clubadmin.stockSummary
import com.nascon.league.data.store.formatPkr
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ArenaTextField
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.ConfirmDialog
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Arena
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Outlives the screen so the "saved" snackbar is still shown after the form closes. */
private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

@Composable
private fun SavedEffect(flow: kotlinx.coroutines.flow.Flow<Unit>, onBack: () -> Unit) {
    val snackbar = LocalSnackbarHostState.current
    val msg = stringResource(R.string.ca_saved)
    LaunchedEffect(flow) {
        flow.collect {
            uiScope.launch { snackbar.showSnackbar(msg) }
            onBack()
        }
    }
}

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun RowLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Arena.colors.inkSoft)
}

// ============================================================ E17 edit club

@Composable
fun EditClubScreen(clubId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val vm: EditClubViewModel = viewModel(key = "ca-club-$clubId", factory = EditClubViewModel.factory(container, clubId))
    val s by vm.state.collectAsStateWithLifecycle()
    SavedEffect(vm.saved, onBack)
    val guard = rememberDiscardGuard(s.dirty, onBack)
    if (!PhaseGate(s.phase, s.loadError, vm::load, onBack, modifier)) return
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    FormLayout(
        modifier,
        bottom = {
            SecondaryButton(stringResource(R.string.ca_cancel), guard.attemptLeave, Modifier.weight(1f))
            PrimaryButton(
                stringResource(R.string.ca_club_save), vm::save, Modifier.weight(1f),
                loading = s.saving,
            )
        },
    ) {
        SaveErrorAlert(s.saveError)
        ArenaTextField(
            s.name, vm::onName, stringResource(R.string.ca_club_name), Modifier.fillMaxWidth(),
            errorText = s.nameError?.text(),
        )
        ArenaTextField(
            s.logoUrl, vm::onLogo, stringResource(R.string.ca_club_logo), Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.ca_club_logo_hint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            errorText = s.logoError?.text(),
        )
        ArenaTextField(s.venueName, vm::onVenueName, stringResource(R.string.ca_club_venue), Modifier.fillMaxWidth())
        ArenaTextField(
            s.lat, vm::onLat, stringResource(R.string.ca_club_lat), Modifier.fillMaxWidth(),
            keyboardOptions = num, supportingText = stringResource(R.string.ca_club_coords_hint),
            errorText = s.coordErrors.lat?.text(),
        )
        ArenaTextField(
            s.lng, vm::onLng, stringResource(R.string.ca_club_lng), Modifier.fillMaxWidth(),
            keyboardOptions = num, supportingText = stringResource(R.string.ca_club_lng_hint),
            errorText = s.coordErrors.lng?.text(),
        )
        Heading(stringResource(R.string.ca_club_socials))
        s.socials.forEachIndexed { i, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArenaTextField(
                        row.key, { vm.onSocialKey(i, it) }, stringResource(R.string.ca_club_social_key),
                        Modifier.fillMaxWidth(), placeholder = stringResource(R.string.ca_club_social_key_hint),
                    )
                    ArenaTextField(
                        row.value, { vm.onSocialValue(i, it) }, stringResource(R.string.ca_club_social_value),
                        Modifier.fillMaxWidth(), placeholder = stringResource(R.string.ca_club_social_value_hint),
                        errorText = s.socialErrors[i]?.text(),
                    )
                }
                IconButton(onClick = { vm.removeSocial(i) }, modifier = Modifier.padding(top = 26.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ca_club_social_remove, i + 1), tint = Arena.colors.liveFg)
                }
            }
        }
        SecondaryButton(stringResource(R.string.ca_club_social_add), vm::addSocial, Modifier.fillMaxWidth(), icon = Icons.Default.Add)
    }
}

// ============================================================ E18 staff

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditStaffScreen(clubId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val vm: EditStaffViewModel = viewModel(key = "ca-staff-$clubId", factory = EditStaffViewModel.factory(container, clubId))
    val s by vm.state.collectAsStateWithLifecycle()
    SavedEffect(vm.saved, onBack)
    val guard = rememberDiscardGuard(s.dirty, onBack)
    if (!PhaseGate(s.phase, s.loadError, vm::load, onBack, modifier)) return
    FormLayout(
        modifier,
        bottom = {
            SecondaryButton(
                stringResource(R.string.ca_staff_add), vm::add, Modifier.weight(1f), icon = Icons.Default.PersonAdd,
            )
            PrimaryButton(stringResource(R.string.ca_staff_save), vm::save, Modifier.weight(1f), loading = s.saving)
        },
    ) {
        SaveErrorAlert(s.saveError)
        if (s.tooMany) SaveErrorAlert(stringResource(R.string.ca_staff_limit))
        if (s.rows.isEmpty()) {
            Text(stringResource(R.string.ca_staff_empty_title), style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.ca_staff_empty_message), style = MaterialTheme.typography.bodyMedium, color = Arena.colors.inkMid)
        }
        s.rows.forEachIndexed { i, row ->
            ArenaCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Heading(stringResource(R.string.ca_staff_member_n, i + 1), Modifier.weight(1f))
                        IconButton(onClick = { vm.remove(row.key) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = if (row.name.isBlank()) stringResource(R.string.ca_staff_remove_blank)
                                else stringResource(R.string.ca_staff_remove, row.name),
                                tint = Arena.colors.liveFg,
                            )
                        }
                    }
                    ArenaTextField(
                        row.name, { vm.onName(row.key, it) }, stringResource(R.string.ca_staff_name), Modifier.fillMaxWidth(),
                        errorText = s.errors[row.key]?.text(),
                    )
                    ArenaTextField(
                        row.role, { vm.onRole(row.key, it) }, stringResource(R.string.ca_staff_role), Modifier.fillMaxWidth(),
                        placeholder = stringResource(R.string.ca_staff_role_hint),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        ROLE_SUGGESTIONS.forEach { role ->
                            val picked = row.role.trim().equals(role, ignoreCase = true)
                            SuggestionPill(role, picked, stringResource(R.string.ca_staff_role_suggest, role)) { vm.onRole(row.key, role) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionPill(text: String, selected: Boolean, description: String, onClick: () -> Unit) {
    val c = Arena.colors
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) c.brand else c.chipBg)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = androidx.compose.ui.graphics.Color.White)
                Box(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) androidx.compose.ui.graphics.Color.White else c.brand)
        }
    }
}

// ============================================================ E19 products

@Composable
fun ClubProductsScreen(
    clubId: String,
    onNewProduct: () -> Unit,
    onEdit: (productId: String) -> Unit,
    onStock: (productId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm: ClubProductsViewModel = viewModel(key = "ca-products-$clubId", factory = ClubProductsViewModel.factory(container, clubId))
    val s by vm.state.collectAsStateWithLifecycle()
    // Edits happen on other screens, so reload whenever we come back.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        var first = true
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { if (first) first = false else vm.refresh() }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (s.phase == LoadPhase.Ready) {
        HeaderSubtitle(
            if (s.clubName.isBlank()) stringResource(R.string.ca_products_subtitle_plain, s.products.size)
            else stringResource(R.string.ca_products_subtitle, s.clubName, s.products.size),
        )
    }
    if (!PhaseGate(s.phase, s.error, vm::refresh, onBack, modifier)) return
    Column(modifier.fillMaxSize()) {
        if (s.error != null) Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) { SaveErrorAlert(stringResource(R.string.ca_products_error_stale)) }
        if (s.products.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Inventory2,
                title = stringResource(R.string.ca_products_empty_title),
                message = stringResource(R.string.ca_products_empty_message),
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(s.products, key = { it.productId }) { p -> ProductRow(p, onEdit = { onEdit(p.productId) }, onStock = { onStock(p.productId) }) }
            }
        }
        PrimaryButton(
            stringResource(R.string.ca_products_new), onNewProduct,
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), icon = Icons.Default.Add,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductRow(p: AdminProductDto, onEdit: () -> Unit, onStock: () -> Unit) {
    val c = Arena.colors
    val summary = stockSummary(p.variants)
    val range = priceRange(p.variants)
    ArenaCard(Modifier.fillMaxWidth(), onClick = onEdit) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(c.brandLight), contentAlignment = Alignment.Center) {
                    if (p.imageUrl != null) {
                        AsyncImage(p.imageUrl, contentDescription = stringResource(R.string.ca_products_image, p.name), modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Default.Inventory2, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val price = when {
                        range == null -> ""
                        range.first == range.second -> formatPkr(range.first)
                        else -> stringResource(R.string.ca_products_price_range, formatPkr(range.first), formatPkr(range.second))
                    }
                    val sizes = if (p.variants.size > 1) " · " + stringResource(R.string.ca_products_variants, p.variants.size) else ""
                    Text(price + sizes, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = c.inkSoft)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (p.active) StatusChip(stringResource(R.string.ca_products_active), Icons.Default.Visibility, tone = ChipTone.Success)
                else StatusChip(stringResource(R.string.ca_products_inactive), Icons.Default.VisibilityOff, tone = ChipTone.Neutral)
                when (summary.kind) {
                    StockKind.Out -> StatusChip(stringResource(R.string.ca_products_out), Icons.Default.Block, tone = ChipTone.Error)
                    StockKind.Low -> StatusChip(stringResource(R.string.ca_products_low, summary.count), Icons.Default.Warning, tone = ChipTone.Warning)
                    StockKind.InStock -> StatusChip(stringResource(R.string.ca_products_instock, summary.count), Icons.Default.CheckCircle, tone = ChipTone.Success)
                }
            }
            if (p.variants.isNotEmpty()) {
                SecondaryButton(
                    stringResource(R.string.ca_products_stock), onStock, Modifier.fillMaxWidth(),
                    icon = Icons.Default.Inventory2,
                )
            }
        }
    }
}

// ============================================================ E20 product edit

@Composable
fun ProductEditScreen(clubId: String, productId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val vm: ProductEditViewModel = viewModel(
        key = "ca-product-$clubId-$productId",
        factory = ProductEditViewModel.factory(container, clubId, productId),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    SavedEffect(vm.saved, onBack)
    val guard = rememberDiscardGuard(s.dirty, onBack)
    if (!PhaseGate(s.phase, s.loadError, vm::load, onBack, modifier)) return
    val price = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    val whole = KeyboardOptions(keyboardType = KeyboardType.Number)
    FormLayout(
        modifier,
        bottom = {
            SecondaryButton(stringResource(R.string.ca_cancel), guard.attemptLeave, Modifier.weight(1f))
            PrimaryButton(
                stringResource(if (s.isNew) R.string.ca_product_create else R.string.ca_product_save),
                vm::save, Modifier.weight(1f), loading = s.saving,
            )
        },
    ) {
        SaveErrorAlert(s.saveError)
        ArenaTextField(s.name, vm::onName, stringResource(R.string.ca_product_name), Modifier.fillMaxWidth(), errorText = s.nameError?.text())
        ArenaTextField(
            s.category, vm::onCategory, stringResource(R.string.ca_product_category), Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.ca_product_category_hint),
        )
        ArenaTextField(
            s.imageUrl, vm::onImage, stringResource(R.string.ca_product_image), Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.ca_club_logo_hint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), errorText = s.imageError?.text(),
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = s.active, role = Role.Switch, onValueChange = vm::onActive),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ca_product_active), style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink)
                Text(
                    stringResource(if (s.active) R.string.ca_product_active_on else R.string.ca_product_active_off),
                    style = MaterialTheme.typography.bodySmall, color = Arena.colors.inkSoft,
                )
            }
            Switch(checked = s.active, onCheckedChange = null)
        }
        Heading(stringResource(R.string.ca_product_variants))
        if (s.noVariants) SaveErrorAlert(stringResource(R.string.ca_err_no_variants))
        if (s.tooMany) SaveErrorAlert(stringResource(R.string.ca_product_limit))
        s.variants.forEachIndexed { i, v ->
            val e = s.variantErrors[i]
            ArenaCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Heading(stringResource(R.string.ca_product_variant_n, i + 1), Modifier.weight(1f))
                        IconButton(onClick = { vm.removeVariant(v.key) }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ca_product_variant_remove, i + 1), tint = Arena.colors.liveFg)
                        }
                    }
                    ArenaTextField(v.size, { vm.onSize(v.key, it) }, stringResource(R.string.ca_product_size), Modifier.fillMaxWidth())
                    ArenaTextField(
                        v.color, { vm.onColor(v.key, it) }, stringResource(R.string.ca_product_color), Modifier.fillMaxWidth(),
                        errorText = when (e?.label) {
                            null -> null
                            FormError.Required -> stringResource(R.string.ca_err_variant_label)
                            else -> e?.label?.text()
                        },
                    )
                    ArenaTextField(
                        v.price, { vm.onPrice(v.key, it) }, stringResource(R.string.ca_product_price), Modifier.fillMaxWidth(),
                        placeholder = stringResource(R.string.ca_product_price_hint), keyboardOptions = price,
                        errorText = e?.price?.text(),
                    )
                    if (v.variantId == null) {
                        ArenaTextField(
                            v.stock, { vm.onStock(v.key, it.filter(Char::isDigit).take(6)) },
                            stringResource(R.string.ca_product_start_stock), Modifier.fillMaxWidth(),
                            keyboardOptions = whole, errorText = e?.stock?.text(),
                        )
                    }
                }
            }
        }
        SecondaryButton(stringResource(R.string.ca_product_variant_add), vm::addVariant, Modifier.fillMaxWidth(), icon = Icons.Default.Add)
        if (!s.isNew) RowLabel(stringResource(R.string.ca_product_stock_note))
    }
}

// ============================================================ E21 stock

@Composable
fun StockScreen(clubId: String, productId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val vm: StockViewModel = viewModel(key = "ca-stock-$clubId-$productId", factory = StockViewModel.factory(container, clubId, productId))
    val s by vm.state.collectAsStateWithLifecycle()
    SavedEffect(vm.saved, onBack)
    if (s.phase == LoadPhase.Ready && s.productName.isNotBlank()) HeaderSubtitle(stringResource(R.string.ca_stock_header, s.productName))
    if (!PhaseGate(s.phase, s.loadError, vm::load, onBack, modifier)) return
    if (s.confirming) {
        val changed = s.changed
        val outCount = changed.count { parseStock(it.text) == 0 }
        ConfirmDialog(
            title = stringResource(R.string.ca_stock_confirm_title),
            message = if (outCount > 0) stringResource(R.string.ca_stock_confirm_message_out, changed.size, outCount)
            else stringResource(R.string.ca_stock_confirm_message, changed.size),
            confirmLabel = stringResource(R.string.ca_stock_save),
            onConfirm = vm::confirmSave,
            onDismiss = vm::dismissConfirm,
        )
    }
    FormLayout(
        modifier,
        bottom = {
            PrimaryButton(stringResource(R.string.ca_stock_save), vm::requestSave, Modifier.weight(1f), loading = s.saving, enabled = s.rows.isNotEmpty())
        },
    ) {
        SaveErrorAlert(s.saveError)
        if (s.rows.isEmpty()) Text(stringResource(R.string.ca_stock_no_variants), style = MaterialTheme.typography.bodyMedium, color = Arena.colors.inkMid)
        s.rows.forEach { row ->
            val now = parseStock(row.text)
            ArenaCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.label.ifBlank { "-" }, style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink)
                    when {
                        now == 0 -> StatusChip(stringResource(R.string.ca_stock_row_out), Icons.Default.Block, tone = ChipTone.Error)
                        now != null && now <= com.nascon.league.data.clubadmin.LOW_STOCK_AT ->
                            StatusChip(stringResource(R.string.ca_stock_row_low, now), Icons.Default.Warning, tone = ChipTone.Warning)
                        now != null -> StatusChip(stringResource(R.string.ca_stock_row_in, now), Icons.Default.CheckCircle, tone = ChipTone.Success)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stepper(Icons.Default.Remove, stringResource(R.string.ca_stock_decrease, row.label)) { vm.step(row.variantId, -1) }
                        ArenaTextField(
                            row.text, { vm.onText(row.variantId, it) }, stringResource(R.string.ca_stock_field, row.label),
                            Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            errorText = if (row.variantId in s.invalid) stringResource(R.string.ca_err_stock) else null,
                        )
                        Stepper(Icons.Default.Add, stringResource(R.string.ca_stock_increase, row.label)) { vm.step(row.variantId, 1) }
                    }
                }
            }
        }
        RowLabel(stringResource(R.string.ca_stock_note))
    }
}

@Composable
private fun Stepper(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val c = Arena.colors
    Box(
        Modifier
            .padding(top = 26.dp)
            .size(48.dp)
            .clip(CircleShape)
            .border(BorderStroke(2.dp, c.brand), CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = c.brand)
    }
}
