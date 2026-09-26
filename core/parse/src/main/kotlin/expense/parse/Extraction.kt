package expense.parse

import expense.money.Money
import java.time.LocalDateTime

data class TransactionCandidate(
    val kind: TransactionKind,
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
)

data class Extraction(
    val confidence: Int,
    val candidates: List<TransactionCandidate>,
) {
    init {
        require(confidence in 0..100) { "confidence must be 0..100" }
    }
}
