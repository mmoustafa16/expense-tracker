package expense.android.storage

enum class SearchField {
    BODY,
    MERCHANT,
    REFERENCE,
    AMOUNT,
    CATEGORY,
}

data class SearchMatch(
    val transactionId: String?,
    val smsId: String?,
    val fields: Set<SearchField>,
    val sortAt: Long = 0,
)
