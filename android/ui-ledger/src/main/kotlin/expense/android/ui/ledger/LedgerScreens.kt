package expense.android.ui.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import expense.android.storage.DatabaseLockedException
import expense.android.storage.LedgerSession
import expense.android.ui.common.CategoryOptions
import expense.android.ui.common.DropdownField
import expense.android.ui.common.MoneyFormat
import expense.android.ui.common.UiResult
import expense.categories.Category
import expense.ledger.LedgerState
import expense.ledger.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

data class LedgerScanSummary(
    val title: String,
    val summary: String,
    val reviewLine: String,
    val detail: String,
    val note: String?,
)

@Composable
fun LedgerRoute(
    session: LedgerSession,
    refreshEpoch: Int,
    scan: LedgerScanSummary? = null,
    onOpenSearch: () -> Unit,
    onOpenAccount: (String) -> Unit,
    onOpenTransaction: (String) -> Unit,
    onOpenCategories: () -> Unit,
) {
    var tree by remember { mutableStateOf<LedgerTree?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refreshEpoch) {
        try {
            tree = withContext(Dispatchers.IO) { LedgerSessionBindings.tree(session) }
            message = null
        } catch (_: DatabaseLockedException) {
            message = "Unlock the ledger to continue."
        }
    }
    val loaded = tree
    if (loaded == null) {
        CircularProgressIndicator(Modifier.padding(24.dp))
        return
    }
    LedgerScreen(loaded, message, scan, onOpenSearch, onOpenAccount, onOpenTransaction, onOpenCategories)
}

@Composable
fun LedgerScreen(
    tree: LedgerTree,
    message: String?,
    scan: LedgerScanSummary?,
    onOpenSearch: () -> Unit,
    onOpenAccount: (String) -> Unit,
    onOpenTransaction: (String) -> Unit,
    onOpenCategories: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Ledger", style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
        }
        if (scan != null) ScanSummaryCard(scan)
        TextButton(onClick = onOpenCategories) { Text("Categories") }
        message?.let { Text(it) }
        if (tree.banks.isEmpty()) {
            Text("No banks or transactions yet.")
            Text("Financial messages with no verified bank profile stay in Review and are not posted.")
        }
        tree.banks.forEach { bank ->
            Text(bank.institutionId, style = MaterialTheme.typography.titleMedium)
            bank.accounts.forEach { group ->
                TextButton(onClick = { onOpenAccount(group.account.id) }) {
                    Text(group.account.ledgerLabel())
                }
                group.transactions.forEach { transaction ->
                    TransactionRow(transaction) { onOpenTransaction(transaction.id) }
                }
            }
            if (bank.unassigned.isNotEmpty()) {
                Text("No account")
                bank.unassigned.forEach { transaction ->
                    TransactionRow(transaction) { onOpenTransaction(transaction.id) }
                }
            }
        }
    }
}

@Composable
private fun ScanSummaryCard(scan: LedgerScanSummary) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(scan.title, style = MaterialTheme.typography.titleSmall)
            Text(scan.summary)
            Text(scan.reviewLine)
            if (expanded) {
                Text(scan.detail)
                scan.note?.let { Text(it) }
            }
        }
    }
}

@Composable
fun AccountRoute(
    session: LedgerSession,
    accountId: String,
    onOpenTransaction: (String) -> Unit,
) {
    var state by remember { mutableStateOf<LedgerState?>(null) }
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(accountId) {
        val loaded = withContext(Dispatchers.IO) { LedgerSessionBindings.load(session) }
        state = loaded
        name = loaded.accounts.find { it.id == accountId }?.displayName.orEmpty()
    }
    val loaded = state
    val account = loaded?.accounts?.find { it.id == accountId }
    if (loaded == null || account == null) {
        CircularProgressIndicator(Modifier.padding(24.dp))
        return
    }
    val transactions = loaded.transactions.filter { it.accountId == account.id }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(account.ledgerLabel(), style = MaterialTheme.typography.headlineSmall)
        Text("${account.institutionId} · ${account.kind.readable()} · ${account.mask}")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Display name") },
            supportingText = { Text("The bank, kind, and mask stay the same.") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            scope.launch {
                try {
                    val updated = withContext(Dispatchers.IO) {
                        LedgerSessionBindings.renameAccount(session, accountId, name)
                    }
                    state = updated
                    message = null
                } catch (error: IllegalArgumentException) {
                    message = error.message ?: "Couldn't rename that account."
                } catch (_: DatabaseLockedException) {
                    message = "Unlock the ledger to continue."
                }
            }
        }) { Text("Save name") }
        message?.let { Text(it) }
        transactions.forEach { transaction ->
            TransactionRow(transaction) { onOpenTransaction(transaction.id) }
        }
    }
}

@Composable
fun TransactionRoute(
    session: LedgerSession,
    transactionId: String,
    onOpenAnalytics: (String) -> Unit,
) {
    var state by remember { mutableStateOf<LedgerState?>(null) }
    var merchantName by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var applyForward by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(transactionId) {
        val loaded = withContext(Dispatchers.IO) { LedgerSessionBindings.load(session) }
        state = loaded
        val transaction = loaded.transactions.find { it.id == transactionId }
        val merchant = loaded.merchants.find { it.id == transaction?.merchantId }
        merchantName = merchant?.displayName ?: transaction?.merchantRaw.orEmpty()
        categoryId = transaction?.categoryId
    }
    val loaded = state
    val transaction = loaded?.transactions?.find { it.id == transactionId }
    if (loaded == null || transaction == null) {
        CircularProgressIndicator(Modifier.padding(24.dp))
        return
    }
    val categories = CategoryOptions.tree(loaded.categories)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(transactionTitle(loaded, transaction), style = MaterialTheme.typography.headlineSmall)
        Text("${transaction.direction.name.lowercase()} · ${transaction.kind.name.lowercase().replace('_', ' ')}")
        Text(transaction.occurredCivil.format(WHEN))
        message?.let { Text(it) }
        OutlinedTextField(
            value = merchantName,
            onValueChange = { merchantName = it },
            label = { Text("Merchant") },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownField(
            label = "Category",
            options = categories,
            selected = categories.find { it.id == categoryId },
            optionLabel = { "${it.nameEn} · ${it.nameAr}" },
            placeholder = "No category",
            onSelected = { categoryId = it.id },
            onClear = { categoryId = null },
        )
        Text("Also use this correction for later matches")
        Switch(checked = applyForward, onCheckedChange = { applyForward = it })
        Button(onClick = {
            val selected = categoryId
            if (selected == null) {
                message = "Choose a category from the ledger."
                return@Button
            }
            val draft = LedgerCorrections.category(
                transaction = transaction,
                categoryId = selected,
                categories = loaded.categories,
                applyForward = applyForward,
                correctionId = UUID.randomUUID().toString(),
                createdAt = Instant.now(),
            )
            applyCorrection(scope, session, draft, { state = it }, { message = it })
        }) { Text("Save category") }
        Button(onClick = {
            val previous = loaded.merchants.find { it.id == transaction.merchantId }?.displayName ?: transaction.merchantRaw
            val draft = LedgerCorrections.merchant(
                transaction = transaction,
                merchantName = merchantName,
                previousName = previous,
                applyForward = applyForward,
                correctionId = UUID.randomUUID().toString(),
                createdAt = Instant.now(),
            )
            applyCorrection(scope, session, draft, { state = it }, { message = it })
        }) { Text("Save merchant") }
        TextButton(onClick = { onOpenAnalytics(transaction.id) }) { Text("Spending for this transaction") }
    }
}

@Composable
fun CategoriesRoute(session: LedgerSession) {
    var state by remember { mutableStateOf<LedgerState?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        state = withContext(Dispatchers.IO) { LedgerSessionBindings.load(session) }
    }
    val loaded = state ?: run {
        CircularProgressIndicator(Modifier.padding(24.dp))
        return
    }
    CategoriesScreen(
        state = loaded,
        message = message,
        onAdd = { nameEn, nameAr, slug, parentId ->
            val draft = CategoryDrafts.add(nameEn, nameAr, slug, parentId, loaded.categories)
            when (draft) {
                is UiResult.Rejected -> message = draft.message
                is UiResult.Ready -> scope.launch {
                    try {
                        state = withContext(Dispatchers.IO) { LedgerSessionBindings.addCategory(session, draft.value) }
                        message = null
                    } catch (error: IllegalArgumentException) {
                        message = error.message ?: "Couldn't add that category."
                    }
                }
            }
        },
        onUpdate = { category, nameEn, nameAr, parentId ->
            val draft = CategoryDrafts.update(category, nameEn, nameAr, parentId, loaded.categories)
            when (draft) {
                is UiResult.Rejected -> message = draft.message
                is UiResult.Ready -> scope.launch {
                    try {
                        state = withContext(Dispatchers.IO) {
                            LedgerSessionBindings.updateCategory(session, category.id, draft.value)
                        }
                        message = null
                    } catch (error: IllegalArgumentException) {
                        message = error.message ?: "Couldn't update that category."
                    }
                }
            }
        },
    )
}

@Composable
fun CategoriesScreen(
    state: LedgerState,
    message: String?,
    onAdd: (String, String, String, String?) -> Unit,
    onUpdate: (Category, String, String, String?) -> Unit,
) {
    val tree = CategoryOptions.tree(state.categories)
    var nameEn by remember { mutableStateOf("") }
    var nameAr by remember { mutableStateOf("") }
    var slug by remember { mutableStateOf("") }
    var parentId by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Categories", style = MaterialTheme.typography.headlineSmall)
        Text("New categories keep a stable id. Built-in categories stay on the seeded tree.")
        message?.let { Text(it) }
        tree.forEach { category ->
            Text("${category.nameEn} · ${category.nameAr}")
            Text(if (category.system) "Built in · ${category.id}" else "Custom · ${category.id}")
            if (CategoryOptions.canEdit(category)) {
                CategoryEditor(category, tree) { english, arabic, parent ->
                    onUpdate(category, english, arabic, parent)
                }
            }
        }
        Text("Add a category", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(nameEn, { nameEn = it }, label = { Text("English name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(nameAr, { nameAr = it }, label = { Text("Arabic name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            slug,
            { slug = it },
            label = { Text("Id") },
            supportingText = { Text("This id does not change when you rename the category.") },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownField(
            label = "Parent",
            options = tree,
            selected = tree.find { it.id == parentId },
            optionLabel = { it.nameEn },
            placeholder = "No parent",
            onSelected = { parentId = it.id },
            onClear = { parentId = null },
        )
        Button(onClick = { onAdd(nameEn, nameAr, slug, parentId) }) { Text("Add category") }
    }
}

@Composable
private fun CategoryEditor(
    category: Category,
    tree: List<Category>,
    onSave: (String, String, String?) -> Unit,
) {
    var nameEn by remember(category.id) { mutableStateOf(category.nameEn) }
    var nameAr by remember(category.id) { mutableStateOf(category.nameAr) }
    var parentId by remember(category.id) { mutableStateOf(category.parentId) }
    OutlinedTextField(nameEn, { nameEn = it }, label = { Text("English name") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(nameAr, { nameAr = it }, label = { Text("Arabic name") }, modifier = Modifier.fillMaxWidth())
    DropdownField(
        label = "Parent",
        options = tree.filter { it.id != category.id },
        selected = tree.find { it.id == parentId },
        optionLabel = { it.nameEn },
        placeholder = "No parent",
        onSelected = { parentId = it.id },
        onClear = { parentId = null },
    )
    TextButton(onClick = { onSave(nameEn, nameAr, parentId) }) { Text("Save ${category.id}") }
}

@Composable
private fun TransactionRow(transaction: Transaction, onOpen: () -> Unit) {
    TextButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(transaction.merchantRaw ?: transaction.kind.name.lowercase())
            Text("${MoneyFormat.format(transaction.amount)} · ${transaction.direction.name.lowercase()}")
        }
    }
}

private fun transactionTitle(state: LedgerState, transaction: Transaction): String {
    val merchant = state.merchants.find { it.id == transaction.merchantId }?.displayName
    return merchant ?: transaction.merchantRaw ?: "Transaction"
}

private fun applyCorrection(
    scope: kotlinx.coroutines.CoroutineScope,
    session: LedgerSession,
    draft: UiResult<expense.ledger.Correction>,
    onState: (LedgerState) -> Unit,
    onMessage: (String?) -> Unit,
) {
    when (draft) {
        is UiResult.Rejected -> onMessage(draft.message)
        is UiResult.Ready -> scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { LedgerSessionBindings.correct(session, draft.value) }
                onState(updated)
                onMessage(null)
            } catch (error: IllegalArgumentException) {
                onMessage(error.message ?: "Couldn't save that correction.")
            } catch (_: DatabaseLockedException) {
                onMessage("Unlock the ledger to continue.")
            }
        }
    }
}

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
