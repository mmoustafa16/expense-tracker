package expense.parse

enum class TransactionKind {
    PURCHASE,
    REFUND,
    REVERSAL,
    FAILED,
    TRANSFER_IN,
    TRANSFER_OUT,
    CASH_WITHDRAWAL,
    INSTALLMENT,
    FEE,
    INCOME,
    UNKNOWN,
}

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
}
