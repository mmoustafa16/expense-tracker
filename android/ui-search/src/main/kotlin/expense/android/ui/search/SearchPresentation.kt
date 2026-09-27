package expense.android.ui.search

import expense.android.storage.SearchField
import expense.android.storage.SearchMatch
import expense.android.ui.common.CategoryOptions
import expense.android.ui.common.MoneyFormat
import expense.ledger.LedgerState

data class SearchHitView(
    val transactionId: String?,
    val smsId: String?,
    val fields: List<SearchField>,
    val title: String,
    val subtitle: String,
)

object SearchPresentation {
    const val MAX_HITS: Int = 20

    fun present(state: LedgerState, matches: List<SearchMatch>): List<SearchHitView> {
        val tree = CategoryOptions.tree(state.categories)
        return matches.map { match ->
            val transaction = match.transactionId?.let { id -> state.transactions.find { it.id == id } }
            val sms = match.smsId?.let { id -> state.messages.find { it.id == id } }
            val merchant = transaction?.merchantId?.let { id -> state.merchants.find { it.id == id } }
            val category = CategoryOptions.find(tree, transaction?.categoryId)
            val title = merchant?.displayName
                ?: transaction?.merchantRaw?.takeIf { it.isNotBlank() }
                ?: sms?.sender?.takeIf { it.isNotBlank() }
                ?: "Saved item"
            val parts = mutableListOf<String>()
            if (transaction != null) parts += MoneyFormat.format(transaction.amount)
            if (category != null) parts += category.nameEn
            transaction?.reference?.takeIf { it.isNotBlank() }?.let { parts += it }
            if (match.fields.isNotEmpty()) {
                parts += match.fields.joinToString(prefix = "matched ") { it.name.lowercase() }
            }
            if (SearchField.BODY in match.fields) {
                sms?.body?.takeIf { it.isNotBlank() }?.let { parts += excerpt(it) }
            }
            SearchHitView(
                transactionId = match.transactionId,
                smsId = match.smsId,
                fields = match.fields.toList(),
                title = title,
                subtitle = parts.joinToString(" · "),
            )
        }
    }

    fun excerpt(body: String, limit: Int = BODY_EXCERPT): String {
        if (body.length <= limit) return body
        return body.take(limit)
    }

    private const val BODY_EXCERPT: Int = 180
}
