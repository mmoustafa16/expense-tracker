package expense.ledger

import expense.parse.TransactionKind

object TransactionLinker {
    private val originalKinds = setOf(
        TransactionKind.PURCHASE,
        TransactionKind.INSTALLMENT,
        TransactionKind.CASH_WITHDRAWAL,
    )

    fun findOriginal(
        transactions: List<Transaction>,
        institutionId: String,
        reference: String?,
    ): Transaction? {
        if (reference.isNullOrBlank()) return null
        return transactions.find { tx ->
            tx.institutionId == institutionId &&
                tx.reference == reference &&
                tx.kind in originalKinds
        }
    }
}
