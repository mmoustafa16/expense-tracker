package expense.ledger

import expense.parse.FinancialEventType

object TransactionLinker {
    private val originatingEvents = setOf(
        FinancialEventType.CARD_PURCHASE,
        FinancialEventType.INSTALLMENT,
        FinancialEventType.CASH_WITHDRAWAL,
        FinancialEventType.BILL_PAYMENT,
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
                tx.eventType in originatingEvents
        }
    }
}
