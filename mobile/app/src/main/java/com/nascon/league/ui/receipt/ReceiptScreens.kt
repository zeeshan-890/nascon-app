package com.nascon.league.ui.receipt

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.receipts.ReceiptEntry
import com.nascon.league.data.store.formatPkr
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ReceiptScreen(orderId: String, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val vm: ReceiptViewModel = viewModel(
        key = "receipt-$orderId",
        factory = ReceiptViewModel.factory(container.receiptStore, orderId),
    )
    val state by vm.state.collectAsState()
    val entry by vm.entry.collectAsState()
    val context = LocalContext.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    val shareFailed = stringResource(R.string.f6r_share_failed)
    val shareTitle = stringResource(R.string.f6r_share_title)
    val savedMsg = stringResource(R.string.f6r_saved_downloads)
    val fullMsg = stringResource(R.string.f6r_storage_full)
    val failMsg = stringResource(R.string.f6r_download_failed)
    var saving by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        ReceiptHeader(orderId, entry)
        val ready = state as? ReceiptUiState.Ready
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SecondaryButton(
                text = stringResource(R.string.f6r_share),
                icon = Icons.Default.Share,
                enabled = ready != null,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (ready != null && !ReceiptActions.share(context, ready.file, shareTitle)) {
                        scope.launch { snackbar.showSnackbar(shareFailed) }
                    }
                },
            )
            SecondaryButton(
                text = stringResource(R.string.f6r_download),
                icon = Icons.Default.Download,
                enabled = ready != null && !saving,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (ready != null) {
                        saving = true
                        scope.launch {
                            val r = ReceiptActions.saveToDownloads(context, ready.file, orderId)
                            saving = false
                            snackbar.showSnackbar(
                                when (r) {
                                    DownloadResult.Saved -> savedMsg
                                    DownloadResult.StorageFull -> fullMsg
                                    DownloadResult.Failed -> failMsg
                                },
                            )
                        }
                    }
                },
            )
        }
        Spacer(Modifier.size(Spacing.sm))
        when (val s = state) {
            ReceiptUiState.Loading -> LoadingBox(stringResource(R.string.f6r_loading))
            ReceiptUiState.NotSavedOffline -> EmptyState(
                icon = Icons.Default.WifiOff,
                title = stringResource(R.string.f6r_offline_title),
                message = stringResource(R.string.f6r_offline_message),
                actionLabel = stringResource(R.string.f6r_try_again),
                onAction = vm::load,
            )
            is ReceiptUiState.Failed -> ErrorState(message = s.message, onRetry = vm::load)
            ReceiptUiState.Corrupt -> CorruptState(onDownloadAgain = vm::downloadAgain)
            is ReceiptUiState.Ready -> PdfViewer(s.pages, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LoadingBox(label: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.size(Spacing.md))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CorruptState(onDownloadAgain: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
    ) {
        Icon(
            Icons.Default.Receipt,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            stringResource(R.string.f6r_corrupt_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        PrimaryButton(text = stringResource(R.string.f6r_download_again), onClick = onDownloadAgain)
    }
}

@Composable
private fun ReceiptHeader(orderId: String, entry: ReceiptEntry?) {
    val ref = ReceiptFormat.orderReference(orderId)
    val total = entry?.takeIf { it.totalMinor >= 0 }?.let { formatPkr(it.totalMinor) }
    val date = entry?.let { ReceiptFormat.paidDate(it.paidAt) }
    val refText = stringResource(R.string.f6r_order_ref, ref)
    val totalText = total?.let { stringResource(R.string.f6r_total, it) }
    val dateText = date?.let { stringResource(R.string.f6r_paid_on, it) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(Spacing.md)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(refText, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (totalText != null) Text(totalText, style = MaterialTheme.typography.bodyLarge)
        if (dateText != null) Text(dateText, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PdfViewer(pages: PdfPages, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(pages.pageCount, key = { it }) { index ->
                val size = pages.pageSizes[index]
                val heightDp = with(density) {
                    (widthPx.toLong() * size.second / size.first.coerceAtLeast(1)).toInt().toDp()
                }
                val bitmap by produceState<Bitmap?>(null, pages, index, widthPx) {
                    value = try {
                        pages.render(index, widthPx)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                val pageLabel = stringResource(R.string.f6r_page_cd, index + 1, pages.pageCount)
                val b = bitmap
                if (b != null) {
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = pageLabel,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = heightDp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            }
        }
    }
}

@Composable
fun MyReceiptsScreen(onOpenReceipt: (String) -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val flow = remember(container) { container.receiptStore.observeIndex() }
    val entries: List<ReceiptEntry>? by flow.collectAsState(initial = null)
    val list = entries
    when {
        list == null -> LoadingBox(stringResource(R.string.f6r_loading_list))
        list.isEmpty() -> EmptyState(
            icon = Icons.Default.Receipt,
            title = stringResource(R.string.f6r_empty_title),
            message = stringResource(R.string.f6r_empty_message),
            modifier = modifier,
        )
        else -> LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(list, key = { it.orderId }) { e -> ReceiptRow(e) { onOpenReceipt(e.orderId) } }
        }
    }
}

@Composable
private fun ReceiptRow(entry: ReceiptEntry, onClick: () -> Unit) {
    val ref = stringResource(R.string.f6r_order_ref, ReceiptFormat.orderReference(entry.orderId))
    val total = entry.totalMinor.takeIf { it >= 0 }?.let { formatPkr(it) }
    val date = ReceiptFormat.paidDate(entry.paidAt)
    val paidText = date?.let { stringResource(R.string.f6r_paid_on, it) }
    val cd = listOfNotNull(ref, total, paidText, stringResource(R.string.f6r_open_receipt))
        .joinToString(", ")
    Card(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = cd
                role = Role.Button
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(Icons.Default.Receipt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(ref, style = MaterialTheme.typography.titleMedium)
                if (paidText != null) {
                    Text(
                        paidText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (total != null) Text(total, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}
