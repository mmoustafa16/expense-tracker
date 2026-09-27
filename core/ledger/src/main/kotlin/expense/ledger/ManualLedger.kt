package expense.ledger

import expense.categories.CategoryCatalog
import expense.categories.CategorySource
import expense.merchants.MerchantDirectory
import expense.merchants.MerchantKey
import expense.money.Money
import expense.parse.AccountKind
import expense.parse.Direction
import expense.parse.TransactionKind
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A user-entered transaction. This type does not accept or return a bank profile.
 */
data class ManualDraft(
    val amount: Money,
    val direction: Direction,
    val kind: TransactionKind,
    val occurredAt: Instant,
    val occurredCivil: LocalDateTime,
    val merchantRaw: String?,
    val categoryId: String?,
    val reference: String?,
    val institutionId: String,
    val accountKind: AccountKind?,
    val accountMask: String?,
    val reviewAttemptId: String?,
    val pipelineVersion: String,
)

object ManualLedger {
    fun post(state: LedgerState, draft: ManualDraft, newId: () -> String): LedgerState {
        val institutionId = draft.institutionId.trim()
        require(institutionId.isNotBlank()) { "institution id is required" }
        val categoryId = draft.categoryId?.trim()?.takeIf { it.isNotEmpty() }?.let { requested ->
            CategoryCatalog.canonicalId(requested, state.categories)
                ?: throw IllegalArgumentException("unknown category")
        }
        val merchantRaw = draft.merchantRaw?.trim()?.takeIf { it.isNotEmpty() }
        val resolved = MerchantDirectory.resolve(merchantRaw, state.merchants, state.aliases, newId)
        val normalized = merchantRaw?.let(MerchantKey::normalize)
        val mask = draft.accountMask?.trim()?.takeIf { it.isNotEmpty() }
        val (withAccount, accountId) = ensureAccount(
            state.copy(merchants = resolved.merchants),
            institutionId,
            draft.accountKind,
            mask,
            draft.amount,
            newId,
        )
        val minute = draft.occurredCivil.format(MINUTE)
        val tx = Transaction(
            id = newId(),
            dedupKey = DedupKey.of(
                institutionId = institutionId,
                mask = mask,
                reference = draft.reference?.trim()?.takeIf { it.isNotEmpty() },
                amount = draft.amount,
                kind = draft.kind,
                merchantKey = normalized,
                minuteBucket = minute,
            ),
            smsId = "",
            institutionId = institutionId,
            accountId = accountId,
            kind = draft.kind,
            status = TransactionStatus.POSTED,
            amount = draft.amount,
            direction = draft.direction,
            occurredAt = draft.occurredAt,
            occurredCivil = draft.occurredCivil,
            occurredSource = OccurredSource.MANUAL,
            merchantRaw = merchantRaw,
            merchantId = resolved.merchant?.id,
            categoryId = categoryId,
            categorySource = if (categoryId == null) CategorySource.UNCATEGORIZED else CategorySource.USER,
            reference = draft.reference?.trim()?.takeIf { it.isNotEmpty() },
            balance = null,
            foreignAmount = null,
            duplicateOfId = null,
            linkedTransactionId = null,
            includeInSpend = SpendPolicy.include(draft.kind, TransactionStatus.POSTED),
            pipelineVersion = draft.pipelineVersion,
            profileVersion = "",
            installmentIndex = null,
            installmentCount = null,
            manual = true,
        )
        var next = withAccount.copy(transactions = withAccount.transactions + tx)
        val attemptId = draft.reviewAttemptId?.trim()?.takeIf { it.isNotEmpty() }
        if (attemptId != null) {
            val attempt = next.attempts.find { it.id == attemptId }
                ?: throw IllegalArgumentException("review item does not exist")
            require(attempt.status.needsReview() || ReviewDismissals.isDismissed(next, attempt)) {
                "review item is not open"
            }
            val sms = next.messages.find { it.id == attempt.smsId }
                ?: throw IllegalArgumentException("review message does not exist")
            next = next.copy(
                transactions = next.transactions.map { row ->
                    if (row.id == tx.id) row.copy(smsId = sms.id) else row
                },
            )
            val evidenceExists = next.evidence.any {
                it.transactionId == tx.id && it.smsId == sms.id && it.role == EvidenceRole.PRIMARY
            }
            if (!evidenceExists) {
                next = next.copy(
                    evidence = next.evidence + TransactionEvidence(
                        id = newId(),
                        transactionId = tx.id,
                        smsId = sms.id,
                        role = EvidenceRole.PRIMARY,
                    ),
                )
            }
            next = ReviewDismissals.dismiss(next, attempt.id)
        }
        return next
    }

    private fun ensureAccount(
        state: LedgerState,
        institutionId: String,
        kind: AccountKind?,
        mask: String?,
        amount: Money,
        newId: () -> String,
    ): Pair<LedgerState, String?> {
        if (kind == null || mask == null) return state to null
        val existing = state.accounts.find {
            it.institutionId == institutionId && it.kind == kind && it.mask == mask
        }
        if (existing != null) return state to existing.id
        val created = Account(
            id = newId(),
            institutionId = institutionId,
            kind = kind,
            mask = mask,
            currency = amount.currency,
        )
        return state.copy(accounts = state.accounts + created) to created.id
    }

    private val MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
}
