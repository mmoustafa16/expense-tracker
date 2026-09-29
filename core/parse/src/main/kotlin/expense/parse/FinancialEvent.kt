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

    /**
     * Roles that can carry the value of this event, most specific first.
     *
     * The event decides which number it means. A fee notice is about the fee, a
     * settlement is about the payment, and a purchase is about the transaction
     * amount, so a message that states a transaction and a fee in the same
     * breath is not ambiguous once the event type is known.
     */
    fun valueRoles(): List<AmountRole> = when (this) {
        FEE -> listOf(AmountRole.FEE_AMOUNT, AmountRole.TRANSACTION_AMOUNT)
        REFUND, REVERSAL -> listOf(AmountRole.REFUND_AMOUNT, AmountRole.TRANSACTION_AMOUNT)
        INSTALLMENT -> listOf(AmountRole.INSTALLMENT_AMOUNT, AmountRole.TRANSACTION_AMOUNT)
        CREDIT_CARD_PAYMENT, BILL_PAYMENT ->
            listOf(AmountRole.PAYMENT_AMOUNT, AmountRole.TRANSACTION_AMOUNT)
        else -> AmountRole.DEFAULT_VALUE_ROLES
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
     * The role names a value some event could be worth, as opposed to a
     * balance, a ceiling, or an advertisement.
     */
    fun isEventValue(): Boolean = this in VALUE_ROLES

    /** Roles that describe the account rather than the event. */
    fun isBalance(): Boolean = when (this) {
        REMAINING_BALANCE, AVAILABLE_BALANCE, AVAILABLE_CREDIT -> true
        else -> false
    }

    companion object {
        /**
         * Roles that can be the value of an event whose type says nothing more
         * specific, in the order a reader would prefer them.
         */
        val DEFAULT_VALUE_ROLES: List<AmountRole> = listOf(
            TRANSACTION_AMOUNT,
            PAYMENT_AMOUNT,
            REFUND_AMOUNT,
            INSTALLMENT_AMOUNT,
        )

        private val VALUE_ROLES: Set<AmountRole> = DEFAULT_VALUE_ROLES.toSet() + FEE_AMOUNT
    }
}

/**
 * Chooses which of the values in a message is the value of the event.
 *
 * The choice is made by role and by the event type, never by position or size,
 * so a remaining balance, a credit ceiling, and a promotional maximum cannot
 * stand in for what actually moved. An event whose own role holds two different
 * values is [AmountResolution.AMBIGUOUS] and belongs to the account holder to
 * settle; a message that names no value at all is [AmountResolution.MISSING].
 */
object EventAmounts {
    fun resolve(amounts: List<RoledAmount>, valueRoles: List<AmountRole>): AmountResolution {
        valueRoles.forEach { role ->
            val claiming = amounts.filter { it.role == role }
            if (claiming.isNotEmpty()) {
                return if (claiming.map { it.amount }.distinct().size > 1) {
                    AmountResolution.AMBIGUOUS
                } else {
                    AmountResolution.RESOLVED
                }
            }
        }
        val unnamed = amounts.filter { it.role == AmountRole.UNKNOWN }
        return if (unnamed.map { it.amount }.distinct().size > 1) {
            AmountResolution.AMBIGUOUS
        } else {
            AmountResolution.MISSING
        }
    }

    fun select(amounts: List<RoledAmount>, valueRoles: List<AmountRole>): RoledAmount? {
        valueRoles.forEach { role ->
            amounts.firstOrNull { it.role == role }?.let { return it }
        }
        return null
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
