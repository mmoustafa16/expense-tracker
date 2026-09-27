package expense.ingest

import expense.categories.RuleSource
import expense.ledger.Correction
import expense.ledger.DuplicateMatcher
import expense.ledger.EvidenceRole
import expense.ledger.LedgerState
import expense.ledger.StoredSms
import expense.ledger.TransactionEvidence
import expense.merchants.AliasSource
import expense.parse.BankMatcher
import expense.parse.BankProfile
import expense.parse.BankRegistry
import expense.parse.Extraction
import expense.parse.FinancialSignal
import expense.parse.ParseAttempt
import expense.parse.ParseStatus
import expense.parse.TemplateExecution
import expense.parse.TemplateRunner
import expense.sms.BodyHash
import expense.sms.InboundSms
import expense.sms.SmsSource

/**
 * Bank-agnostic ingest. [registry] defaults to [BankRegistry.EMPTY].
 * SMS capture adapters and storage implementations sit outside this type.
 */
class IngestPipeline(
    private val registry: BankRegistry = BankRegistry.EMPTY,
    private val ids: IdGenerator = UuidIdGenerator,
) {
    private val matcher = BankMatcher(registry)
    private val poster = LedgerPoster(ids)

    fun ingest(incoming: InboundSms, state: LedgerState = LedgerState.empty()): IngestResult {
        val sms = bounded(incoming)
        val financial = FinancialSignal.present(sms.body)
        if (DuplicateMatcher.providerReplay(state.messages, sms.providerMessageId) != null) {
            return IngestResult(state, status = null, attempt = null, alreadyIngested = true, financial = financial)
        }
        val hash = BodyHash.sha256(sms.body)
        val bodyReplay = DuplicateMatcher.bodyReplay(state.messages, sms.sender, hash, sms.receivedAt)
        if (bodyReplay != null) {
            val duplicate = storedCopy(sms, hash, retainBody = bodyReplay.body != null)
            val linked = poster.attachDuplicateSms(state, bodyReplay.id, duplicate)
            return IngestResult(linked, status = null, attempt = null, alreadyIngested = true, financial = financial)
        }
        val decision = interpret(sms)
        val stored = storedCopy(sms, hash, decision.retainBody)
        val attempt = ParseAttempt(
            id = ids.newId(),
            smsId = stored.id,
            pipelineVersion = PipelineMetadata.VERSION,
            profileId = decision.profile?.id,
            profileVersion = decision.profile?.version,
            templateId = decision.templateId,
            status = decision.status,
            confidence = decision.extraction?.confidence,
            extraction = decision.extraction,
            error = decision.error,
        )
        var next = state.copy(
            messages = state.messages + stored,
            attempts = state.attempts + attempt,
        )
        val matchedProfile = decision.profile != null || decision.status == ParseStatus.AMBIGUOUS
        val posting = decision.status == ParseStatus.PARSED && decision.profile != null && decision.extraction != null
        if (posting) {
            next = poster.post(next, stored, decision.profile!!, decision.extraction!!)
        }
        next = CorrectionOverlay.apply(next, ids)
        return IngestResult(
            state = next,
            status = decision.status,
            attempt = attempt,
            alreadyIngested = false,
            financial = financial,
            matchedProfile = matchedProfile,
            posted = posting,
        )
    }

    fun ingestAll(source: SmsSource, state: LedgerState = LedgerState.empty()): LedgerState {
        return source.messages().fold(state) { acc, sms -> ingest(sms, acc).state }
    }

    fun correct(state: LedgerState, correction: Correction): LedgerState {
        return CorrectionOverlay.apply(state.copy(corrections = state.corrections + correction), ids)
    }

    /**
     * Rebuilds derived rows from retained SMS and reapplies corrections.
     * The bound [BankRegistry] is read and never written.
     */
    fun reparse(state: LedgerState): LedgerState {
        val messages = state.messages.sortedWith(compareBy({ it.receivedAt }, { it.id }))
        val manual = state.transactions.filter { it.manual }
        val manualMerchantIds = manual.mapNotNull { it.merchantId }.toSet()
        var cursor = LedgerState(
            accounts = state.accounts,
            merchants = state.merchants.filter { it.id in manualMerchantIds },
            corrections = state.corrections,
            categories = state.categories,
            reviewDismissals = state.reviewDismissals,
            categoryRules = state.categoryRules.filter { it.source == RuleSource.SYSTEM },
            aliases = state.aliases.filter { it.source == AliasSource.SYSTEM },
        )
        for (message in messages) {
            val body = message.body
            if (body == null) {
                cursor = cursor.copy(messages = cursor.messages + message)
                continue
            }
            cursor = ingest(
                InboundSms(
                    sender = message.sender,
                    body = body,
                    providerMessageId = message.providerMessageId,
                    receivedAt = message.receivedAt,
                ),
                cursor,
            ).state
        }
        if (manual.isEmpty()) return cursor
        return CorrectionOverlay.apply(
            restoreManual(cursor, messages, manual),
            ids,
        )
    }

    private fun restoreManual(
        rebuilt: LedgerState,
        originalMessages: List<StoredSms>,
        manual: List<expense.ledger.Transaction>,
    ): LedgerState {
        val newByIdentity = rebuilt.messages.groupBy { it.replayIdentity() }
        var transactions = rebuilt.transactions
        var evidence = rebuilt.evidence
        for (tx in manual) {
            if (transactions.any { it.dedupKey == tx.dedupKey }) continue
            val oldSms = originalMessages.find { it.id == tx.smsId }
            val newSmsId = if (oldSms == null) {
                tx.smsId
            } else {
                newByIdentity[oldSms.replayIdentity()]?.firstOrNull()?.id ?: tx.smsId
            }
            var keptId = tx.id
            if (transactions.any { it.id == keptId } || rebuilt.messages.any { it.id == keptId }) {
                keptId = ids.newId()
            }
            val kept = tx.copy(id = keptId, smsId = newSmsId)
            transactions = transactions + kept
            val linked = rebuilt.messages.any { it.id == newSmsId }
            val evidenceExists = evidence.any {
                it.transactionId == kept.id && it.smsId == newSmsId && it.role == EvidenceRole.PRIMARY
            }
            if (linked && !evidenceExists) {
                evidence = evidence + TransactionEvidence(
                    id = ids.newId(),
                    transactionId = kept.id,
                    smsId = newSmsId,
                    role = EvidenceRole.PRIMARY,
                )
            }
        }
        return rebuilt.copy(transactions = transactions, evidence = evidence)
    }

    private fun interpret(sms: InboundSms): Interpretation {
        val matched = matcher.match(sms.sender)
        if (matched.size > 1) {
            return Interpretation(status = ParseStatus.AMBIGUOUS, retainBody = true)
        }
        val profile = matched.singleOrNull()
            ?: return if (FinancialSignal.present(sms.body)) {
                Interpretation(status = ParseStatus.UNSUPPORTED, retainBody = true)
            } else {
                Interpretation(status = ParseStatus.IGNORED_NOT_BANK, retainBody = false)
            }
        return when (val execution = TemplateRunner.execute(profile, sms)) {
            TemplateExecution.NoTemplateMatch ->
                if (FinancialSignal.present(sms.body)) {
                    Interpretation(status = ParseStatus.UNSUPPORTED, retainBody = true, profile = profile)
                } else {
                    Interpretation(status = ParseStatus.IGNORED_NOT_BANK, retainBody = false, profile = profile)
                }
            is TemplateExecution.ExtractorFailed -> Interpretation(
                status = ParseStatus.FAILED,
                retainBody = true,
                profile = profile,
                templateId = execution.template.id,
                error = execution.reason,
            )
            is TemplateExecution.Extracted -> Interpretation(
                status = if (execution.postable) ParseStatus.PARSED else ParseStatus.LOW_CONFIDENCE,
                retainBody = true,
                profile = profile,
                templateId = execution.template.id,
                extraction = execution.extraction,
            )
        }
    }

    private fun bounded(incoming: InboundSms): InboundSms {
        val sender = incoming.sender.trim()
        val body = if (incoming.body.length <= PipelineMetadata.MAX_BODY_CHARS) {
            incoming.body
        } else {
            incoming.body.take(PipelineMetadata.MAX_BODY_CHARS)
        }
        return incoming.copy(sender = sender, body = body)
    }

    private fun StoredSms.replayIdentity(): String {
        return listOf(sender, bodyHash, receivedAt.toEpochMilli().toString(), providerMessageId.orEmpty())
            .joinToString("|")
    }

    private fun storedCopy(sms: InboundSms, hash: String, retainBody: Boolean): StoredSms {
        return StoredSms(
            id = ids.newId(),
            sender = sms.sender,
            body = if (retainBody) sms.body else null,
            bodyHash = hash,
            providerMessageId = sms.providerMessageId,
            receivedAt = sms.receivedAt,
        )
    }

    private data class Interpretation(
        val status: ParseStatus,
        val retainBody: Boolean,
        val profile: BankProfile? = null,
        val templateId: String? = null,
        val extraction: Extraction? = null,
        val error: String? = null,
    )
}
