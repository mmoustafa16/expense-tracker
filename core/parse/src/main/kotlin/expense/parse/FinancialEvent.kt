package expense.parse

import expense.money.Money

/**
 * What the message says happened. This is the meaning of the event, not its
 * effect on spending: [SpendEffect] answers that separately, because the same
 * event type can settle a liability for one instrument and be an expense for
 * another.
 *
 * [NOT_FINANCIAL] is an outcome, not a fallback. A message labelled
 * [NOT_FINANCIAL] is ignored and never held for review.
 */
enum class FinancialEventType {
    CARD_PURCHASE,
    BANK_TRANSFER,
    CASH_WITHDRAWAL,
    CREDIT_CARD_PAYMENT,
    BILL_PAYMENT,
    REFUND,
    REVERSAL,
    FEE,
    INSTALLMENT,
    INCOME,
    BALANCE_NOTIFICATION,
    STATEMENT,
    PAYMENT_DUE,
    FAILED_TRANSACTION,
    DECLINED_TRANSACTION,
    OTHER_FINANCIAL,
    NOT_FINANCIAL,
    ;

    /** The message is about money, whether or not any money moved. */
    fun isFinancialEvent(): Boolean = this != NOT_FINANCIAL

    /**
     * The event type can move money out of or into an account. A balance
     * notice, a statement, a due reminder, and a failed attempt cannot, so they
     * are financial events that never become ledger rows.
     */
    fun canMoveMoney(): Boolean = when (this) {
        CARD_PURCHASE,
        BANK_TRANSFER,
        CASH_WITHDRAWAL,
        CREDIT_CARD_PAYMENT,
        BILL_PAYMENT,
        REFUND,
        REVERSAL,
        FEE,
        INSTALLMENT,
        INCOME,
        OTHER_FINANCIAL,
        -> true
        BALANCE_NOTIFICATION,
        STATEMENT,
        PAYMENT_DUE,
        FAILED_TRANSACTION,
        DECLINED_TRANSACTION,
        NOT_FINANCIAL,
        -> false
    }

    /** Default effect on spending before any instrument-aware refinement. */
    fun defaultSpendEffect(): SpendEffect = when (this) {
        CARD_PURCHASE, BILL_PAYMENT, CASH_WITHDRAWAL, FEE, INSTALLMENT -> SpendEffect.SPEND
        REFUND -> SpendEffect.SPEND_REVERSAL
        BANK_TRANSFER -> SpendEffect.TRANSFER_EXTERNAL
        CREDIT_CARD_PAYMENT -> SpendEffect.LIABILITY_SETTLEMENT
        INCOME -> SpendEffect.INCOME
        REVERSAL,
        BALANCE_NOTIFICATION,
        STATEMENT,
        PAYMENT_DUE,
        FAILED_TRANSACTION,
        DECLINED_TRANSACTION,
        OTHER_FINANCIAL,
        NOT_FINANCIAL,
        -> SpendEffect.NONE
    }
}

/**
 * What the event does to the money the account holder has spent.
 *
 * Paying a credit-card bill moves money, so it belongs in the ledger, but the
 * purchases it settles were already counted. It is a
 * [LIABILITY_SETTLEMENT] and contributes nothing to spending.
 */
enum class SpendEffect {
    /** An expense. Adds to spending. */
    SPEND,

    /** Money returned for an earlier expense. Subtracts from spending. */
    SPEND_REVERSAL,

    /** Between two accounts the holder owns. Nets to zero and is not spending. */
    TRANSFER_INTERNAL,

    /** Out to someone else. Money left, but it is not a purchase. */
    TRANSFER_EXTERNAL,

    /** Repays a card or loan balance whose charges were already counted. */
    LIABILITY_SETTLEMENT,

    /** Money in. */
    INCOME,

    /** No effect on any total. */
    NONE,
    ;

    /** Whether spend analytics aggregates this row. */
    fun countsTowardSpend(): Boolean = this == SPEND || this == SPEND_REVERSAL
}

/**
 * What one amount in the message means. Every number the extractor keeps gets
 * a role, so a balance or an advertised ceiling is never mistaken for the
 * transaction amount and two amounts in one message are not an ambiguity.
 */
enum class AmountRole {
    TRANSACTION_AMOUNT,
    REMAINING_BALANCE,
    AVAILABLE_BALANCE,
    AVAILABLE_CREDIT,
    PAYMENT_AMOUNT,
    MINIMUM_PAYMENT,
    INSTALLMENT_AMOUNT,
    FEE_AMOUNT,
    TAX_AMOUNT,
    REFUND_AMOUNT,
    ORIGINAL_TRANSACTION_AMOUNT,
    PROMOTIONAL_AMOUNT,

    /** A number that is money but whose role the extractor could not name. */
    UNKNOWN,
    ;

    /**
     * Roles that can carry the value of the event itself. A message states one
     * of these once; more than one distinct value is a genuine ambiguity.
     */
    fun isEventValue(): Boolean = when (this) {
        TRANSACTION_AMOUNT, PAYMENT_AMOUNT, INSTALLMENT_AMOUNT, REFUND_AMOUNT -> true
        else -> false
    }

    /** Roles that describe the account rather than the event. */
    fun isBalance(): Boolean = when (this) {
        REMAINING_BALANCE, AVAILABLE_BALANCE, AVAILABLE_CREDIT -> true
        else -> false
    }
}

/** One money value found in a message, with the role it plays there. */
data class RoledAmount(
    val amount: Money,
    val role: AmountRole,
    val token: String,
    val currencyToken: String,
    val start: Int,
    val end: Int,
)

/** Whether the value of the event could be named. */
enum class AmountResolution {
    RESOLVED,

    /** The message states no money at all. */
    MISSING,

    /** More than one distinct value claims the event role. */
    AMBIGUOUS,
}
