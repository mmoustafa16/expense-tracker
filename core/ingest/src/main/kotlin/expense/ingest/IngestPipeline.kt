package expense.ingest

import expense.categories.RuleSource
import expense.ledger.Correction
import expense.ledger.DuplicateMatcher
import expense.ledger.LedgerState
import expense.ledger.StoredSms
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
        if (DuplicateMatcher.providerReplay(state.messages, sms.providerMessageId) != null) {
            return IngestResult(state, status = null, attempt = null, alreadyIngested = true)
        }
        val hash = BodyHash.sha256(sms.body)
        val bodyReplay = DuplicateMatcher.bodyReplay(state.messages, sms.sender, hash, sms.receivedAt)
        if (bodyReplay != null) {
            val duplicate = storedCopy(sms, hash, retainBody = bodyReplay.body != null)
            val linked = poster.attachDuplicateSms(state, bodyReplay.id, duplicate)
            return IngestResult(linked, status = null, attempt = null, alreadyIngested = true)
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
        if (decision.status == ParseStatus.PARSED && decision.profile != null && decision.extraction != null) {
            next = poster.post(next, stored, decision.profile, decision.extraction)
        }
        next = CorrectionOverlay.apply(next, ids)
        return IngestResult(next, decision.status, attempt, alreadyIngested = false)
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
        var cursor = LedgerState(
            corrections = state.corrections,
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
        return cursor
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
