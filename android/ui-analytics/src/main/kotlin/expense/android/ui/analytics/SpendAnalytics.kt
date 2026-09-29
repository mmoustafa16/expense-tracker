package expense.android.ui.analytics

import expense.android.ui.common.CategoryOptions
import expense.categories.Category
import expense.ledger.Account
import expense.ledger.LedgerState
import expense.ledger.SpendPolicy
import expense.ledger.Transaction
import expense.merchants.Merchant
import expense.money.Currency
import java.time.YearMonth

data class AnalyticsSlice(
    val month: YearMonth? = null,
    val year: Int? = null,
    val institutionId: String? = null,
    val accountId: String? = null,
    val categoryId: String? = null,
    val merchantId: String? = null,
    val transactionId: String? = null,
)

data class SignedTotal(
    val currency: Currency,
    val signedMinor: Long,
)

data class CategoryTotal(
    val categoryId: String,
    val nameEn: String,
    val nameAr: String,
    val parentId: String?,
    val totals: List<SignedTotal>,
)

data class MerchantTotal(
    val merchantId: String?,
    val name: String,
    val totals: List<SignedTotal>,
)

data class AnalyticsOptions(
    val months: List<YearMonth>,
    val institutions: List<String>,
    val accounts: List<Account>,
    val categories: List<Category>,
    val subcategories: List<Category>,
    val merchants: List<Merchant>,
)

data class AnalyticsReport(
    val totals: List<SignedTotal>,
    val categories: List<CategoryTotal>,
    val merchants: List<MerchantTotal>,
    val transactionCount: Int,
    /** Ledger rows in this slice that are excluded from spending. */
    val excludedTransactionCount: Int,
) {
    /**
     * Names the population the totals were taken over.
     *
     * Analytics sums spend transactions, which is a narrower set than the ledger
     * holds: a settled card balance, a transfer, and a voided row are all ledger
     * rows that spending must not include. Saying so is why two screens can show
     * different numbers and both be right.
     */
    fun population(): String {
        val spending = "$transactionCount spend ${if (transactionCount == 1) "transaction" else "transactions"}"
        if (excludedTransactionCount == 0) return spending
        return "$spending · $excludedTransactionCount ledger " +
            (if (excludedTransactionCount == 1) "row" else "rows") + " excluded from spending"
    }
}

/**
 * Spend totals read from a ledger snapshot. Parent rows include descendant
 * categories. Each currency keeps its own total.
 */
object SpendAnalytics {
    fun options(state: LedgerState): AnalyticsOptions {
        val tree = CategoryOptions.tree(state.categories)
        val included = state.transactions.filter { it.includeInSpend }
        return AnalyticsOptions(
            months = included.map { SpendPolicy.spendMonth(it) }.distinct().sortedDescending(),
            institutions = (state.accounts.map { it.institutionId } + included.map { it.institutionId })
                .distinct()
                .sorted(),
            accounts = state.accounts.sortedWith(compareBy({ it.institutionId }, { it.mask }, { it.id })),
            categories = tree.filter { it.parentId == null },
            subcategories = tree.filter { it.parentId != null },
            merchants = state.merchants.sortedBy { it.displayName.lowercase() },
        )
    }

    fun report(state: LedgerState, slice: AnalyticsSlice): AnalyticsReport {
        val tree = CategoryOptions.tree(state.categories)
        val base = state.transactions.filter { matches(it, slice) }
        val scoped = applyCategory(base, tree, slice.categoryId)
        val excluded = state.transactions.filter { !it.includeInSpend && inSlice(it, slice) }
        val showMerchants = slice.categoryId != null || slice.merchantId != null || slice.transactionId != null
        return AnalyticsReport(
            totals = sumByCurrency(scoped),
            categories = categoryRows(tree, base, slice.categoryId),
            merchants = if (showMerchants) merchantRows(state, scoped) else emptyList(),
            transactionCount = scoped.size,
            excludedTransactionCount = excluded.size,
        )
    }

    private fun categoryRows(
        tree: List<Category>,
        base: List<Transaction>,
        selectedId: String?,
    ): List<CategoryTotal> {
        val rows = if (selectedId == null) {
            tree.filter { it.parentId == null }.map { totalFor(it, tree, base) }.filter { it.totals.isNotEmpty() }
        } else {
            val selected = tree.find { it.id == selectedId }
            val children = tree.filter { it.parentId == selectedId }
            listOfNotNull(selected).map { totalFor(it, tree, base) } +
                children.map { totalFor(it, tree, base) }.filter { it.totals.isNotEmpty() }
        }
        val uncategorized = if (selectedId == null) {
            val missing = base.filter { it.categoryId == null }
            if (missing.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    CategoryTotal(
                        categoryId = UNCATEGORIZED,
                        nameEn = "Uncategorized",
                        nameAr = "بدون تصنيف",
                        parentId = null,
                        totals = sumByCurrency(missing),
                    ),
                )
            }
        } else {
            emptyList()
        }
        return rows + uncategorized
    }

    private fun totalFor(category: Category, tree: List<Category>, base: List<Transaction>): CategoryTotal {
        val ids = CategoryOptions.descendants(tree, category.id)
        return CategoryTotal(
            categoryId = category.id,
            nameEn = category.nameEn,
            nameAr = category.nameAr,
            parentId = category.parentId,
            totals = sumByCurrency(base.filter { it.categoryId in ids }),
        )
    }

    private fun merchantRows(state: LedgerState, scoped: List<Transaction>): List<MerchantTotal> {
        val names = state.merchants.associateBy { it.id }
        return scoped.groupBy { merchantKey(it) }
            .map { (_, rows) ->
                val sample = rows.first()
                val merchant = sample.merchantId?.let(names::get)
                MerchantTotal(
                    merchantId = sample.merchantId,
                    name = merchant?.displayName ?: sample.merchantRaw?.takeIf { it.isNotBlank() } ?: "Unknown merchant",
                    totals = sumByCurrency(rows),
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private fun merchantKey(transaction: Transaction): String {
        transaction.merchantId?.let { return "id:$it" }
        val raw = transaction.merchantRaw?.trim().orEmpty()
        return if (raw.isEmpty()) "unknown" else "raw:$raw"
    }

    private fun applyCategory(
        transactions: List<Transaction>,
        tree: List<Category>,
        categoryId: String?,
    ): List<Transaction> {
        if (categoryId == null) return transactions
        if (categoryId == UNCATEGORIZED) return transactions.filter { it.categoryId == null }
        val ids = CategoryOptions.descendants(tree, categoryId)
        return transactions.filter { it.categoryId in ids }
    }

    private fun matches(transaction: Transaction, slice: AnalyticsSlice): Boolean {
        if (!transaction.includeInSpend) return false
        return inSlice(transaction, slice)
    }

    /** Slice filters without the spend rule, so excluded rows can be counted. */
    private fun inSlice(transaction: Transaction, slice: AnalyticsSlice): Boolean {
        val spendMonth = SpendPolicy.spendMonth(transaction)
        if (slice.month != null && spendMonth != slice.month) return false
        if (slice.month == null && slice.year != null && spendMonth.year != slice.year) return false
        if (slice.institutionId != null && transaction.institutionId != slice.institutionId) return false
        if (slice.accountId != null && transaction.accountId != slice.accountId) return false
        if (slice.merchantId != null && transaction.merchantId != slice.merchantId) return false
        if (slice.transactionId != null && transaction.id != slice.transactionId) return false
        return true
    }

    private fun sumByCurrency(transactions: List<Transaction>): List<SignedTotal> {
        return transactions
            .groupBy { it.amount.currency }
            .map { (currency, rows) ->
                SignedTotal(currency, rows.sumOf { SpendPolicy.signedMinor(it) })
            }
            .sortedBy { it.currency.code }
    }

    const val UNCATEGORIZED: String = "uncategorized"
}

object AnalyticsCalendar {
    fun years(months: List<YearMonth>): List<Int> = months.map { it.year }.distinct().sortedDescending()

    fun monthsFor(months: List<YearMonth>, year: Int?): List<YearMonth> {
        val scoped = if (year == null) months else months.filter { it.year == year }
        return scoped.distinct().sortedDescending()
    }
}
