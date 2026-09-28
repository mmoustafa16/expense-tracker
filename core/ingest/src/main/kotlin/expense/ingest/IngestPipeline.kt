package expense.ingest

import expense.categories.RuleSource
import expense.ledger.Correction
import expense.ledger.DuplicateMatcher
import expense.ledger.EvidenceRole
import expense.ledger.LedgerState
import expense.ledger.StoredSms
import expense.ledger.TransactionEvidence
import expense.merchants.AliasSource
import expense.intelligence.AmountRole
import expense.intelligence.ClassificationDecision
import expense.intelligence.FinancialSmsIntelligence
import expense.intelligence.InstitutionBootstrap
import expense.intelligence.MoneyDirection
import expense.intelligence.RegisteredSender
import expense.intelligence.SmsText
import expense.intelligence.TransactionClass
import expense.parse.AccountKind
import expense.parse.BankMatcher
import expense.parse.BankProfile
import expense.parse.BankRegistry
import expense.parse.Direction
import expense.parse.Extraction
import expense.parse.ParseAttempt
import expense.parse.ParseStatus
import expense.parse.TemplateExecution
import expense.parse.TemplateRunner
import expense.parse.TransactionCandidate
import expense.parse.TransactionKind
import expense.sms.BodyHash
import expense.sms.InboundSms
import expense.sms.SmsSource

/**
 * Bank-agnostic ingest. [registry] defaults to [BankRegistry.EMPTY].
 * Every institution, including ones not on this device, uses the same
 * discovery, classification, extraction, and validation pipeline.
 * SMS capture adapters and storage implementations sit outside this type.
 */
class IngestPipeline(
    private val registry: BankRegistry = BankRegistry.EMPTY,
    private val ids: IdGenerator = UuidIdGenerator,
    private val intelligence: FinancialSmsIntelligence = FinancialSmsIntelligence.deterministic(
        registry.profiles.map { RegisteredSender(it.id, it.displayName, it.senderIds) } +
            InstitutionBootstrap.records,
    ),
) {
    private val matcher = BankMatcher(registry)
    private val poster = LedgerPoster(ids)

    fun ingest(incoming: InboundSms, state: LedgerState = LedgerState.empty()): IngestResult {
        val sms = bounded(incoming)
        val assessment = intelligence.assess(SmsText(sms.sender, sms.body))
        val financial = assessment.classification.type.isLedgerCandidate()
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
        val decision = interpret(sms, assessment)
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
        val matchedProfile = decision.profile?.senderIds?.isNotEmpty() == true ||
            decision.status == ParseStatus.AMBIGUOUS
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

    private fun interpret(sms: InboundSms, assessment: ClassificationDecision): Interpretation {
        val matched = matcher.match(sms.sender)
        val profile = matched.singleOrNull()
        if (matched.size > 1) {
            if (nonFinancial(assessment)) {
                return Interpretation(status = ParseStatus.IGNORED_NOT_BANK, retainBody = false)
            }
            return Interpretation(status = ParseStatus.AMBIGUOUS, retainBody = true)
        }
        if (profile != null) {
            when (val execution = TemplateRunner.execute(profile, sms)) {
                is TemplateExecution.ExtractorFailed -> return Interpretation(
                    status = ParseStatus.FAILED,
                    retainBody = true,
                    profile = profile,
                    templateId = execution.template.id,
                    error = execution.reason,
                )
                is TemplateExecution.Extracted -> {
                    val agrees = execution.postable && !contradicts(assessment, execution.extraction)
                    return Interpretation(
                        status = if (agrees) ParseStatus.PARSED else ParseStatus.LOW_CONFIDENCE,
                        retainBody = true,
                        profile = profile,
                        templateId = execution.template.id,
                        extraction = execution.extraction,
                    )
                }
                TemplateExecution.NoTemplateMatch -> Unit
            }
        }
        if (assessment.postable) {
            return Interpretation(
                status = ParseStatus.PARSED,
                retainBody = true,
                profile = profile ?: carrierProfile(assessment),
                extraction = intelligenceExtraction(sms.body, assessment),
            )
        }
        if (assessment.classification.type.isLedgerCandidate()) {
            return Interpretation(status = ParseStatus.UNSUPPORTED, retainBody = true, profile = profile)
        }
        return Interpretation(status = ParseStatus.IGNORED_NOT_BANK, retainBody = false, profile = profile)
    }

    /** Sender identity does not keep a non-financial message. */
    private fun nonFinancial(assessment: ClassificationDecision): Boolean {
        return !assessment.classification.type.isLedgerCandidate() &&
            assessment.classification.confidence >= FinancialSmsIntelligence.HIGH_CONFIDENCE
    }

    private fun contradicts(assessment: ClassificationDecision, extraction: Extraction): Boolean {
        val understood = assessment.entities.amount ?: return false
        if (assessment.entities.amountRole != AmountRole.TRANSACTION) return false
        val templated = extraction.candidates.firstOrNull()?.amount ?: return false
        return understood != templated
    }

    private fun carrierProfile(assessment: ClassificationDecision): BankProfile {
        val known = checkNotNull(assessment.discovery.verifiedInstitution) {
            "A ledger post requires one verified institution"
        }
        return BankProfile(
            id = known.institutionId,
            version = INTELLIGENCE_VERSION,
            displayName = known.displayName,
            senderIds = emptySet(),
            templates = emptyList(),
        )
    }

    private fun intelligenceExtraction(body: String, assessment: ClassificationDecision): Extraction {
        val kind = ledgerKind(assessment.classification.type, assessment.entities.direction)
        val direction = when (assessment.entities.direction) {
            MoneyDirection.CREDIT -> Direction.CREDIT
            MoneyDirection.DEBIT, null -> Direction.DEBIT
        }
        val mask = assessment.entities.accountMask
        val accountKind = when {
            mask == null -> null
            Regex("""(?i)\bcredit card\b""").containsMatchIn(body) -> AccountKind.CREDIT_CARD
            Regex("""(?i)\bdebit card\b""").containsMatchIn(body) -> AccountKind.DEBIT_CARD
            Regex("""(?i)\baccount\b""").containsMatchIn(body) -> AccountKind.ACCOUNT
            else -> null
        }
        return Extraction(
            confidence = assessment.classification.confidence,
            candidates = listOf(
                TransactionCandidate(
                    kind = kind,
                    amount = assessment.entities.amount,
                    direction = direction,
                    merchantRaw = assessment.entities.merchant,
                    occurredAt = assessment.entities.occurredAt,
                    reference = assessment.entities.reference,
                    accountMask = mask,
                    accountKind = accountKind,
                    balance = assessment.entities.balance,
                ),
            ),
        )
    }

    private fun ledgerKind(type: TransactionClass, direction: MoneyDirection?): TransactionKind {
        return when (type) {
            TransactionClass.CARD_PURCHASE, TransactionClass.PAYMENT -> TransactionKind.PURCHASE
            TransactionClass.TRANSFER ->
                if (direction == MoneyDirection.CREDIT) {
                    TransactionKind.TRANSFER_IN
                } else {
                    TransactionKind.TRANSFER_OUT
                }
            TransactionClass.CASH_WITHDRAWAL -> TransactionKind.CASH_WITHDRAWAL
            TransactionClass.REFUND -> TransactionKind.REFUND
            TransactionClass.REVERSAL -> TransactionKind.REVERSAL
            TransactionClass.FEE -> TransactionKind.FEE
            else -> TransactionKind.UNKNOWN
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

    private companion object {
        const val INTELLIGENCE_VERSION: String = "intelligence"
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
