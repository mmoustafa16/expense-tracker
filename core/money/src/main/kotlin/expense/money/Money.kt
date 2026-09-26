package expense.money

/**
 * A non-negative amount in minor units. Sign lives on the ledger direction,
 * so a refund is a positive credit rather than a negative balance.
 */
data class Money(
    val amountMinor: Long,
    val currency: Currency,
) {
    init {
        require(amountMinor >= 0) { "amount must be zero or positive minor units" }
    }
}
