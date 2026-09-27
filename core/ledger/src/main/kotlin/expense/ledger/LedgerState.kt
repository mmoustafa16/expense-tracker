package expense.ledger

import expense.categories.CategoryRule
import expense.merchants.Merchant
import expense.merchants.MerchantAlias
import expense.parse.ParseAttempt
import java.time.Instant

data class StoredSms(
    val id: String,
    val sender: String,
    val body: String?,
    val bodyHash: String,
    val providerMessageId: String?,
    val receivedAt: Instant,
)

enum class EvidenceRole {
    PRIMARY,
    DUPLICATE,
    REVERSAL_NOTICE,
}

data class TransactionEvidence(
    val id: String,
    val transactionId: String,
    val smsId: String,
    val role: EvidenceRole,
)

data class PossibleDuplicate(
    val id: String,
    val transactionId: String,
    val otherTransactionId: String,
)

data class LedgerState(
    val messages: List<StoredSms> = emptyList(),
    val attempts: List<ParseAttempt> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val evidence: List<TransactionEvidence> = emptyList(),
    val merchants: List<Merchant> = emptyList(),
    val aliases: List<MerchantAlias> = emptyList(),
    val categoryRules: List<CategoryRule> = emptyList(),
    val corrections: List<Correction> = emptyList(),
    val possibleDuplicates: List<PossibleDuplicate> = emptyList(),
) {
    fun reviewQueue(): List<ParseAttempt> = attempts.filter { it.status.needsReview() }

    companion object {
        fun empty(): LedgerState = LedgerState()
    }
}
