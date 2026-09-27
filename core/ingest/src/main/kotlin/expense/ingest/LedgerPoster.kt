package expense.ingest

import expense.categories.CategoryResolver
import expense.categories.CategorySource
import expense.ledger.Account
import expense.ledger.DedupKey
import expense.ledger.DuplicateMatcher
import expense.ledger.EvidenceRole
import expense.ledger.KindCategories
import expense.ledger.LedgerState
import expense.ledger.OccurredSource
import expense.ledger.PossibleDuplicate
import expense.ledger.SpendPolicy
import expense.ledger.StoredSms
import expense.ledger.Transaction
import expense.ledger.TransactionEvidence
import expense.ledger.TransactionLinker
import expense.ledger.TransactionStatus
import expense.merchants.MerchantDirectory
import expense.merchants.MerchantKey
import expense.money.Money
import expense.parse.BankProfile
import expense.parse.Extraction
import expense.parse.TransactionCandidate
import expense.parse.TransactionKind
import java.time.format.DateTimeFormatter

internal class LedgerPoster(
    private val ids: IdGenerator,
) {
    fun post(
        state: LedgerState,
        message: StoredSms,
        profile: BankProfile,
        extraction: Extraction,
    ): LedgerState {
        var current = state
        var anchorId: String? = null
        for (candidate in extraction.candidates) {
            val amount = candidate.amount ?: continue
            if (candidate.kind == TransactionKind.REVERSAL) {
                current = postReversal(current, message, profile, candidate, amount)
                continue
            }
            val resend = DuplicateMatcher.referenceResend(
                current.transactions,
                profile.id,
                candidate.reference,
                candidate.kind,
            )
            if (resend != null) {
                current = addEvidence(current, resend.id, message.id, EvidenceRole.DUPLICATE)
                continue
            }
            val posted = appendTransaction(current, message, profile, candidate, amount, anchorId)
            current = posted.state
            if (candidate.kind == TransactionKind.PURCHASE || candidate.kind == TransactionKind.INSTALLMENT) {
                anchorId = posted.transactionId
            }
        }
        return current
    }

    fun attachDuplicateSms(state: LedgerState, priorSmsId: String, duplicate: StoredSms): LedgerState {
        var next = state.copy(messages = state.messages + duplicate)
        for (tx in state.transactions.filter { it.smsId == priorSmsId }) {
            next = addEvidence(next, tx.id, duplicate.id, EvidenceRole.DUPLICATE)
        }
        return next
    }

    private fun postReversal(
        state: LedgerState,
        message: StoredSms,
        profile: BankProfile,
        candidate: TransactionCandidate,
        amount: Money,
    ): LedgerState {
        val resend = DuplicateMatcher.referenceResend(
            state.transactions,
            profile.id,
            candidate.reference,
            TransactionKind.REVERSAL,
        )
        if (resend != null) {
            return addEvidence(state, resend.id, message.id, EvidenceRole.DUPLICATE)
        }
        val original = TransactionLinker.findOriginal(state.transactions, profile.id, candidate.reference)
        var current = state
        if (original != null) {
            current = current.copy(
                transactions = current.transactions.map { tx ->
                    if (tx.id == original.id) {
                        tx.copy(status = TransactionStatus.VOIDED, includeInSpend = false)
                    } else {
                        tx
                    }
                },
            )
            current = addEvidence(current, original.id, message.id, EvidenceRole.REVERSAL_NOTICE)
        }
        return appendTransaction(
            state = current,
            message = message,
            profile = profile,
            candidate = candidate,
            amount = amount,
            anchorId = original?.id,
        ).state
    }

    private fun appendTransaction(
        state: LedgerState,
        message: StoredSms,
        profile: BankProfile,
        candidate: TransactionCandidate,
        amount: Money,
        anchorId: String?,
    ): Posted {
        val civil = candidate.occurredAt ?: CairoClock.civilFrom(message.receivedAt)
        val occurredSource = if (candidate.occurredAt != null) {
            OccurredSource.SMS_FIELD
        } else {
            OccurredSource.RECEIVED_AT
        }
        val resolved = MerchantDirectory.resolve(
            raw = candidate.merchantRaw,
            merchants = state.merchants,
            aliases = state.aliases,
            newId = ids::newId,
        )
        val merchant = resolved.merchant
        val normalized = candidate.merchantRaw?.let(MerchantKey::normalize)
        val (categoryId, categorySource) = categorize(candidate.kind, merchant?.id, normalized, state)
        val mask = candidate.accountMask?.takeIf { it.isNotBlank() }
        val (withAccount, accountId) = ensureAccount(
            state.copy(merchants = resolved.merchants),
            profile.id,
            candidate,
            amount,
            mask,
        )
        val linked = when (candidate.kind) {
            TransactionKind.FEE -> anchorId
            TransactionKind.REFUND -> TransactionLinker.findOriginal(
                withAccount.transactions,
                profile.id,
                candidate.reference,
            )?.id
            TransactionKind.REVERSAL -> anchorId
            else -> null
        }
        val status = TransactionStatus.POSTED
        val tx = Transaction(
            id = ids.newId(),
            dedupKey = DedupKey.of(
                institutionId = profile.id,
                mask = mask,
                reference = candidate.reference,
                amount = amount,
                kind = candidate.kind,
                merchantKey = normalized,
                minuteBucket = civil.format(MINUTE),
            ),
            smsId = message.id,
            institutionId = profile.id,
            accountId = accountId,
            kind = candidate.kind,
            status = status,
            amount = amount,
            direction = candidate.direction,
            occurredAt = CairoClock.instantFrom(civil),
            occurredCivil = civil,
            occurredSource = occurredSource,
            merchantRaw = candidate.merchantRaw,
            merchantId = merchant?.id,
            categoryId = categoryId,
            categorySource = categorySource,
            reference = candidate.reference,
            balance = candidate.balance,
            foreignAmount = candidate.foreignAmount,
            duplicateOfId = null,
            linkedTransactionId = linked,
            includeInSpend = SpendPolicy.include(candidate.kind, status),
            pipelineVersion = PipelineMetadata.VERSION,
            profileVersion = profile.version,
            installmentIndex = candidate.installmentIndex,
            installmentCount = candidate.installmentCount,
        )
        var next = withAccount.copy(transactions = withAccount.transactions + tx)
        next = addEvidence(next, tx.id, message.id, EvidenceRole.PRIMARY)
        val near = DuplicateMatcher.nearMatches(
            transactions = next.transactions.filter { it.id != tx.id },
            accounts = next.accounts,
            smsHashes = next.messages.associate { it.id to it.bodyHash },
            candidateSmsHash = message.bodyHash,
            institutionId = profile.id,
            mask = mask,
            amount = amount,
            kind = candidate.kind,
            occurredAt = tx.occurredAt,
            reference = candidate.reference,
        )
        val flags = near.map { other ->
            val (left, right) = sortedPair(tx.id, other.id)
            PossibleDuplicate(ids.newId(), left, right)
        }.filter { flag ->
            next.possibleDuplicates.none {
                it.transactionId == flag.transactionId && it.otherTransactionId == flag.otherTransactionId
            }
        }
        next = next.copy(possibleDuplicates = next.possibleDuplicates + flags)
        return Posted(next, tx.id)
    }

    private fun categorize(
        kind: TransactionKind,
        merchantId: String?,
        normalizedKey: String?,
        state: LedgerState,
    ): Pair<String?, CategorySource> {
        val customCategoryIds = state.categories.flatMap { listOf(it.id, it.slug) }.toSet()
        val ruled = CategoryResolver.resolve(merchantId, normalizedKey, state.categoryRules, customCategoryIds)
        val kindDefault = KindCategories.defaultSlug(kind)
        return when {
            ruled != null && ruled.merchantSpecific -> ruled.categoryId to ruled.source
            kindDefault != null -> kindDefault to CategorySource.RULE
            ruled != null -> ruled.categoryId to ruled.source
            else -> null to CategorySource.UNCATEGORIZED
        }
    }

    private fun ensureAccount(
        state: LedgerState,
        institutionId: String,
        candidate: TransactionCandidate,
        amount: Money,
        mask: String?,
    ): Pair<LedgerState, String?> {
        val kind = candidate.accountKind
        if (mask == null || kind == null) return state to null
        val existing = state.accounts.find {
            it.institutionId == institutionId && it.kind == kind && it.mask == mask
        }
        if (existing != null) return state to existing.id
        val created = Account(
            id = ids.newId(),
            institutionId = institutionId,
            kind = kind,
            mask = mask,
            currency = amount.currency,
        )
        return state.copy(accounts = state.accounts + created) to created.id
    }

    private fun addEvidence(
        state: LedgerState,
        transactionId: String,
        smsId: String,
        role: EvidenceRole,
    ): LedgerState {
        val exists = state.evidence.any {
            it.transactionId == transactionId && it.smsId == smsId && it.role == role
        }
        if (exists) return state
        val evidence = TransactionEvidence(ids.newId(), transactionId, smsId, role)
        return state.copy(evidence = state.evidence + evidence)
    }

    private fun sortedPair(left: String, right: String): Pair<String, String> {
        return if (left <= right) left to right else right to left
    }

    private data class Posted(
        val state: LedgerState,
        val transactionId: String,
    )

    companion object {
        private val MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    }
}
