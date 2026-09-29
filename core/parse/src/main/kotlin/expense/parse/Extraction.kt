package expense.parse

import expense.money.Money
import java.time.LocalDateTime

data class TransactionCandidate(
    val eventType: FinancialEventType,
    val spendEffect: SpendEffect,
    val amount: Money?,
    val direction: Direction,
    val merchantRaw: String?,
    val occurredAt: LocalDateTime?,
    val reference: String?,
    val accountMask: String?,
    val accountKind: AccountKind?,
    val balance: Money? = null,
    val foreignAmount: Money? = null,
    val installmentIndex: Int? = null,
    val installmentCount: Int? = null,
    /**
     * Every other money value the message stated, with its role. The event
     * value itself is [amount]; these are the balances, limits, fees, and
     * advertised figures that sit beside it.
     */
    val relatedAmounts: List<RoledAmount> = emptyList(),
)

data class Extraction(
    val confidence: Int,
    val candidates: List<TransactionCandidate>,
) {
    init {
        require(confidence in 0..100) { "confidence must be 0..100" }
    }
}
