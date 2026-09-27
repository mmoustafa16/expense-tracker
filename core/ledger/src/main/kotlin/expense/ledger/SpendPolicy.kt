package expense.ledger

import expense.parse.Direction
import expense.parse.TransactionKind
import java.time.YearMonth

object SpendPolicy {
    fun include(kind: TransactionKind, status: TransactionStatus): Boolean {
        if (status == TransactionStatus.VOIDED || status == TransactionStatus.EXCLUDED) {
            return false
        }
        return when (kind) {
            TransactionKind.PURCHASE,
            TransactionKind.INSTALLMENT,
            TransactionKind.FEE,
            TransactionKind.CASH_WITHDRAWAL,
            TransactionKind.REFUND,
            -> true
            TransactionKind.REVERSAL,
            TransactionKind.FAILED,
            TransactionKind.TRANSFER_IN,
            TransactionKind.TRANSFER_OUT,
            TransactionKind.INCOME,
            TransactionKind.UNKNOWN,
            -> false
        }
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

object KindCategories {
    fun defaultSlug(kind: TransactionKind): String? {
        return when (kind) {
            TransactionKind.CASH_WITHDRAWAL -> "cash"
            TransactionKind.FEE -> "fees"
            TransactionKind.TRANSFER_IN, TransactionKind.TRANSFER_OUT -> "transfers"
            else -> null
        }
    }
}
