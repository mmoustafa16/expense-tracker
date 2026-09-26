package expense.ledger

import expense.categories.CategorySource
import expense.money.Money
import expense.parse.Direction
import expense.parse.TransactionKind
import java.time.Instant
import java.time.LocalDateTime

enum class TransactionStatus {
    POSTED,
    PENDING_REVIEW,
    EXCLUDED,
    VOIDED,
}

enum class OccurredSource {
    SMS_FIELD,
    RECEIVED_AT,
}

data class Transaction(
    val id: String,
    val dedupKey: String,
    val smsId: String,
    val institutionId: String,
    val accountId: String?,
    val kind: TransactionKind,
    val status: TransactionStatus,
    val amount: Money,
    val direction: Direction,
    val occurredAt: Instant,
    val occurredCivil: LocalDateTime,
    val occurredSource: OccurredSource,
    val merchantRaw: String?,
    val merchantId: String?,
    val categoryId: String?,
    val categorySource: CategorySource,
    val reference: String?,
    val balance: Money?,
    val foreignAmount: Money?,
    val duplicateOfId: String?,
    val linkedTransactionId: String?,
    val includeInSpend: Boolean,
    val pipelineVersion: String,
    val profileVersion: String,
    val installmentIndex: Int?,
    val installmentCount: Int?,
)
