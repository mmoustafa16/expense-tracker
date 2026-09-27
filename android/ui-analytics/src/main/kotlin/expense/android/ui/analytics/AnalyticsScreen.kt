package expense.android.ui.analytics

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import expense.android.storage.LedgerSession
import expense.android.ui.common.CategoryOptions
import expense.android.ui.common.DropdownField
import expense.android.ui.common.MoneyFormat
import expense.categories.Category
import expense.ledger.Account
import expense.ledger.LedgerState
import expense.merchants.Merchant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

@Composable
fun AnalyticsRoute(
    session: LedgerSession,
    refreshEpoch: Int,
    initialTransactionId: String?,
) {
    var loaded by remember { mutableStateOf<LedgerState?>(null) }
    var slice by remember(initialTransactionId) { mutableStateOf(AnalyticsSlice(transactionId = initialTransactionId)) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refreshEpoch) {
        loaded = withContext(Dispatchers.IO) { AnalyticsSession.load(session) }
    }
    val state = loaded
    if (state == null) {
        CircularProgressIndicator(Modifier.padding(24.dp))
        return
    }
    val options = remember(state) { SpendAnalytics.options(state) }
    val report = remember(state, slice) { SpendAnalytics.report(state, slice) }
    AnalyticsScreen(
        options = options,
        slice = slice,
        report = report,
        onSlice = { slice = it },
        onReload = {
            scope.launch {
                loaded = withContext(Dispatchers.IO) { AnalyticsSession.load(session) }
            }
        },
    )
}

@Composable
fun AnalyticsScreen(
    options: AnalyticsOptions,
    slice: AnalyticsSlice,
    report: AnalyticsReport,
    onSlice: (AnalyticsSlice) -> Unit,
    onReload: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Spending", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Totals stay in one currency. A parent category includes its subcategories.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item { MonthChips(options.months, slice, onSlice) }
        item {
            SliceMenus(options, slice, onSlice)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSlice(AnalyticsSlice()) }) { Text("Clear") }
                Button(onClick = onReload) { Text("Reload") }
            }
        }
        item {
            Text("Total", style = MaterialTheme.typography.titleMedium)
            if (report.totals.isEmpty()) {
                Text("No spending in this slice.")
            } else {
                report.totals.forEach { total ->
                    Text(MoneyFormat.format(total.signedMinor, total.currency), style = MaterialTheme.typography.titleLarge)
                }
            }
            Text("${report.transactionCount} transactions")
        }
        item { HorizontalDivider() }
        item { Text("Categories", style = MaterialTheme.typography.titleMedium) }
        if (report.categories.isEmpty()) {
            item { Text("No category totals.") }
        } else {
            items(report.categories, key = { it.categoryId }) { row ->
                val indent = if (row.parentId == null) "" else "    "
                ListItem(
                    headlineContent = { Text(indent + row.nameEn) },
                    supportingContent = {
                        Text(row.totals.joinToString { MoneyFormat.format(it.signedMinor, it.currency) }.ifEmpty { "0" })
                    },
                    modifier = Modifier.fillMaxWidth(),
                    trailingContent = {
                        if (row.categoryId != SpendAnalytics.UNCATEGORIZED && slice.categoryId != row.categoryId) {
                            Button(onClick = { onSlice(slice.copy(categoryId = row.categoryId)) }) {
                                Text("Open")
                            }
                        }
                    },
                )
            }
        }
        if (report.merchants.isNotEmpty()) {
            item { Text("Merchants", style = MaterialTheme.typography.titleMedium) }
            items(report.merchants, key = { "${it.merchantId}:${it.name}" }) { row ->
                ListItem(
                    headlineContent = { Text(row.name) },
                    supportingContent = {
                        Text(row.totals.joinToString { MoneyFormat.format(it.signedMinor, it.currency) })
                    },
                )
            }
        }
    }
}

@Composable
private fun MonthChips(
    months: List<YearMonth>,
    slice: AnalyticsSlice,
    onSlice: (AnalyticsSlice) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = slice.month == null,
            onClick = { onSlice(slice.copy(month = null)) },
            label = { Text("All months") },
        )
        months.forEach { month ->
            FilterChip(
                selected = slice.month == month,
                onClick = { onSlice(slice.copy(month = month)) },
                label = { Text(month.toString()) },
            )
        }
    }
}

@Composable
private fun SliceMenus(
    options: AnalyticsOptions,
    slice: AnalyticsSlice,
    onSlice: (AnalyticsSlice) -> Unit,
) {
    val accounts = options.accounts.filter { slice.institutionId == null || it.institutionId == slice.institutionId }
    val subcategories = options.subcategories.filter { slice.categoryId == null || it.parentId == slice.categoryId || it.id == slice.categoryId }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownField(
            label = "Bank",
            options = options.institutions,
            selected = slice.institutionId,
            optionLabel = { it },
            placeholder = "All banks",
            onSelected = { onSlice(slice.copy(institutionId = it, accountId = null)) },
            onClear = { onSlice(slice.copy(institutionId = null, accountId = null)) },
        )
        DropdownField(
            label = "Account or card",
            options = accounts,
            selected = accounts.find { it.id == slice.accountId },
            optionLabel = { accountLabel(it) },
            placeholder = "All accounts",
            onSelected = { onSlice(slice.copy(accountId = it.id, institutionId = it.institutionId)) },
            onClear = { onSlice(slice.copy(accountId = null)) },
        )
        DropdownField(
            label = "Category",
            options = options.categories,
            selected = options.categories.find { it.id == slice.categoryId },
            optionLabel = CategoryOptions::label,
            placeholder = "All categories",
            onSelected = { onSlice(slice.copy(categoryId = it.id)) },
            onClear = { onSlice(slice.copy(categoryId = null)) },
        )
        DropdownField(
            label = "Subcategory",
            options = subcategories,
            selected = subcategories.find { it.id == slice.categoryId },
            optionLabel = { subcategoryLabel(it, options.categories) },
            placeholder = "All subcategories",
            onSelected = { onSlice(slice.copy(categoryId = it.id)) },
            onClear = { onSlice(slice.copy(categoryId = null)) },
        )
        DropdownField(
            label = "Merchant",
            options = options.merchants,
            selected = options.merchants.find { it.id == slice.merchantId },
            optionLabel = { it.displayName },
            placeholder = "All merchants",
            onSelected = { onSlice(slice.copy(merchantId = it.id)) },
            onClear = { onSlice(slice.copy(merchantId = null)) },
        )
        OutlinedTextField(
            value = slice.transactionId.orEmpty(),
            onValueChange = { onSlice(slice.copy(transactionId = it.trim().ifEmpty { null })) },
            label = { Text("Transaction") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun accountLabel(account: Account): String {
    val name = account.displayName.ifBlank { account.mask }
    return "$name · ${account.institutionId}"
}

private fun subcategoryLabel(category: Category, parents: List<Category>): String {
    val parent = parents.find { it.id == category.parentId }?.nameEn
    return if (parent == null) category.nameEn else "$parent / ${category.nameEn}"
}
