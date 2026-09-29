package expense.parse

enum class Direction {
    DEBIT,
    CREDIT,
}

enum class AccountKind {
    DEBIT_CARD,
    CREDIT_CARD,
    ACCOUNT,
    WALLET,
    PREPAID,
    MEEZA,
    CARD,
    UNSPECIFIED,
    ;

    /** True when charges on this instrument are a debt that a payment settles. */
    fun isCredit(): Boolean = this == CREDIT_CARD
}
