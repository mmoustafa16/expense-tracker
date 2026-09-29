package expense.android.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import expense.android.storage.DatabaseLockedException
import expense.android.storage.LedgerSession
import expense.android.ui.common.CategoryOptions
import expense.android.ui.common.DropdownField
import expense.android.ui.common.UiResult
import expense.categories.Category
import expense.ingest.CairoClock
import expense.parse.AccountKind
import expense.parse.Direction
import expense.parse.TransactionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun ReviewRoute(
    session: LedgerSession,
    refreshEpoch: Int,
    onEnterManual: (String) -> Unit,
) {
    var page by remember { mutableStateOf(ReviewSession.peek(session, 0) ?: ReviewPage(emptyList(), 0, 0)) }
    var offset by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(page.rows.isEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingDismiss by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refreshEpoch, offset) {
        if (page.rows.isEmpty()) loading = true
        try {
            page = withContext(Dispatchers.IO) { ReviewSession.page(session, offset) }
            offset = page.offset
            message = null
        } catch (_: DatabaseLockedException) {
            message = "Unlock the ledger to continue."
        }
        loading = false
    }
    if (loading && page.rows.isEmpty()) {
        CircularProgressIndicator(Modifier.padding(24.dp))
        return
    }
    ReviewScreen(
        page = page,
        message = message,
        pendingDismiss = pendingDismiss,
        onAskDismiss = { pendingDismiss = it },
        onCancelDismiss = { pendingDismiss = null },
        onConfirmDismiss = { attemptId ->
            scope.launch {
                try {
                    page = withContext(Dispatchers.IO) { ReviewSession.dismissPage(session, attemptId, offset) }
                    offset = page.offset
                    message = null
                } catch (_: DatabaseLockedException) {
                    message = "Unlock the ledger to continue."
                }
                pendingDismiss = null
            }
        },
        onPrevious = { offset = (page.offset - ReviewQueue.PAGE_SIZE).coerceAtLeast(0) },
        onNext = { offset = page.offset + page.rows.size },
        onEnterManual = onEnterManual,
    )
}

@Composable
fun ReviewScreen(
    page: ReviewPage,
    message: String?,
    pendingDismiss: String?,
    onAskDismiss: (String) -> Unit,
    onCancelDismiss: () -> Unit,
    onConfirmDismiss: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onEnterManual: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Review", style = MaterialTheme.typography.headlineSmall)
            Text("Messages that look like transactions, and could not be posted, stay here.")
            if (message != null) Text(message)
            if (page.rows.isEmpty()) Text("No messages are waiting for review.")
            if (page.total > ReviewQueue.PAGE_SIZE) {
                val end = page.offset + page.rows.size
                Text("Showing ${page.offset + 1}–$end of ${page.total}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPrevious, enabled = page.offset > 0) { Text("Previous") }
                    TextButton(onClick = onNext, enabled = end < page.total) { Text("Next") }
                }
            }
        }
        items(items = page.rows, key = { it.attemptId }) { row ->
            ListItem(
                headlineContent = { Text("${ReviewQueue.statusLabel(row.status)} · ${row.sender}") },
                supportingContent = {
                    Column {
                        row.reason?.let { Text(ReviewQueue.holdLabel(it), style = MaterialTheme.typography.labelMedium) }
                        Text(
                            row.body.orEmpty(),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
            if (pendingDismiss == row.attemptId) {
                Text("Dismiss this message? The original text stays in the ledger.")
                TextButton(onClick = { onConfirmDismiss(row.attemptId) }) { Text("Dismiss") }
                TextButton(onClick = onCancelDismiss) { Text("Cancel") }
            } else {
                TextButton(onClick = { onAskDismiss(row.attemptId) }) { Text("Dismiss") }
                TextButton(onClick = { onEnterManual(row.attemptId) }) { Text("Enter manually") }
            }
        }
    }
}

@Composable
fun ManualTransactionRoute(
    session: LedgerSession,
    attemptId: String,
    onPosted: () -> Unit,
) {
    var entry by remember { mutableStateOf<ManualEntry?>(null) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(attemptId) {
        val state = withContext(Dispatchers.IO) { ReviewSession.load(session) }
        categories = CategoryOptions.tree(state.categories)
        entry = ManualEntries.fromReview(
            state.attempts.find { it.id == attemptId },
            CairoClock.civilFrom(java.time.Instant.now()),
        )
    }
    val form = entry
    if (form == null) {
        CircularProgressIndicator(Modifier.padding(24.dp))
        return
    }
    ManualTransactionScreen(
        entry = form,
        categories = categories,
        message = message,
        saving = saving,
        onEntry = { entry = it },
        onMessage = { message = it },
        onSave = { next ->
            entry = next
            when (val result = ManualEntries.draft(next, categories)) {
                is UiResult.Rejected -> message = result.message
                is UiResult.Ready -> {
                    saving = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { ReviewSession.post(session, result.value) }
                            onPosted()
                        } catch (error: IllegalArgumentException) {
                            message = error.message ?: "Couldn't save that transaction."
                        } catch (_: DatabaseLockedException) {
                            message = "Unlock the ledger to continue."
                        } finally {
                            saving = false
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun ManualTransactionScreen(
    entry: ManualEntry,
    categories: List<Category>,
    message: String?,
    saving: Boolean,
    onEntry: (ManualEntry) -> Unit,
    onMessage: (String) -> Unit,
    onSave: (ManualEntry) -> Unit,
) {
    var whenText by remember(entry.occurredCivil) { mutableStateOf(entry.occurredCivil.format(CIVIL)) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Manual transaction", style = MaterialTheme.typography.headlineSmall)
        Text("This records a transaction on the ledger. It does not add or change a bank parser.")
        if (message != null) Text(message)
        OutlinedTextField(
            value = entry.amountText,
            onValueChange = { onEntry(entry.copy(amountText = it)) },
            label = { Text("Amount") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = entry.currencyCode,
            onValueChange = { onEntry(entry.copy(currencyCode = it)) },
            label = { Text("Currency") },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownField(
            label = "Direction",
            options = Direction.entries,
            selected = entry.direction,
            optionLabel = { it.name.lowercase() },
            onSelected = { onEntry(entry.copy(direction = it)) },
        )
        DropdownField(
            label = "Kind",
            options = TransactionKind.entries,
            selected = entry.kind,
            optionLabel = { it.name.lowercase().replace('_', ' ') },
            onSelected = { onEntry(entry.copy(kind = it)) },
        )
        OutlinedTextField(
            value = whenText,
            onValueChange = { whenText = it },
            label = { Text("When") },
            supportingText = { Text("yyyy-MM-dd HH:mm") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = entry.merchantRaw,
            onValueChange = { onEntry(entry.copy(merchantRaw = it)) },
            label = { Text("Merchant") },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownField(
            label = "Category",
            options = categories,
            selected = categories.find { it.id == entry.categoryId },
            optionLabel = { "${it.nameEn} · ${it.nameAr}" },
            placeholder = "No category",
            onSelected = { onEntry(entry.copy(categoryId = it.id)) },
            onClear = { onEntry(entry.copy(categoryId = null)) },
        )
        OutlinedTextField(
            value = entry.reference,
            onValueChange = { onEntry(entry.copy(reference = it)) },
            label = { Text("Reference") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = entry.institutionLabel,
            onValueChange = { onEntry(entry.copy(institutionLabel = it)) },
            label = { Text("Bank label") },
            supportingText = { Text("A name for your ledger. It does not add a bank parser.") },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownField(
            label = "Account kind",
            options = AccountKind.entries,
            selected = entry.accountKind,
            optionLabel = { it.readable() },
            placeholder = "No account",
            onSelected = { onEntry(entry.copy(accountKind = it)) },
            onClear = { onEntry(entry.copy(accountKind = null)) },
        )
        OutlinedTextField(
            value = entry.accountMask,
            onValueChange = { onEntry(entry.copy(accountMask = it)) },
            label = { Text("Account mask") },
            supportingText = { Text("Set both kind and mask, or leave both empty.") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val civil = runCatching { LocalDateTime.parse(whenText.trim(), CIVIL) }.getOrNull()
                if (civil == null) {
                    onMessage("Use a time like 2026-05-01 10:00.")
                    return@Button
                }
                onSave(entry.copy(occurredCivil = civil))
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saving) "Saving" else "Save transaction")
        }
    }
}

private fun AccountKind.readable(): String {
    return when (this) {
        AccountKind.DEBIT_CARD -> "Debit card"
        AccountKind.CREDIT_CARD -> "Credit card"
        AccountKind.ACCOUNT -> "Account"
        AccountKind.WALLET -> "Wallet"
        AccountKind.PREPAID -> "Prepaid"
        AccountKind.MEEZA -> "Meeza"
        AccountKind.CARD -> "Card"
    }
}

private val CIVIL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
