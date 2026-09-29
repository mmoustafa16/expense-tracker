package expense.ledger

import expense.parse.Direction
import expense.parse.FinancialEventType
import expense.parse.SpendEffect
import java.time.YearMonth

/**
 * Whether a posted row adds to what the account holder spent.
 *
 * The decision reads [SpendEffect] and never the event type, so paying a credit
 * card settles a liability instead of repeating the purchases it covers, and a
 * transfer between the holder's own accounts nets to nothing.
 */
object SpendPolicy {
    fun include(spendEffect: SpendEffect, status: TransactionStatus): Boolean {
        if (status == TransactionStatus.VOIDED || status == TransactionStatus.EXCLUDED) {
            return false
        }
        return spendEffect.countsTowardSpend()
    }

    fun signedMinor(transaction: Transaction): Long {
        if (!transaction.includeInSpend) return 0
        return when (transaction.direction) {
            Direction.DEBIT -> transaction.amount.amountMinor
            Direction.CREDIT -> -transaction.amount.amountMinor
        }
    }

    fun spendMonth(transaction: Transaction): YearMonth {
        return YearMonth.from(transaction.occurredCivil)
    }
}

object EventCategories {
    fun defaultSlug(eventType: FinancialEventType): String? {
        return when (eventType) {
            FinancialEventType.CASH_WITHDRAWAL -> "cash"
            FinancialEventType.FEE -> "fees"
            FinancialEventType.BANK_TRANSFER -> "transfers"
            FinancialEventType.CREDIT_CARD_PAYMENT -> "transfers"
            else -> null
        }
    }
}
