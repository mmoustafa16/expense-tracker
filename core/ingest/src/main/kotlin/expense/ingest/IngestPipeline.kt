package expense.ingest

import expense.categories.RuleSource
import expense.intelligence.ClassificationDecision
import expense.intelligence.FinancialSmsIntelligence
import expense.intelligence.InstitutionBootstrap
import expense.intelligence.MutableSenderEvidenceLedger
import expense.intelligence.PaymentInstruments
import expense.intelligence.RegisteredSender
import expense.intelligence.RoutingOutcome
import expense.intelligence.SenderEvidence
import expense.intelligence.SmsText
import expense.ledger.Correction
import expense.ledger.DuplicateMatcher
import expense.ledger.EvidenceRole
import expense.ledger.LedgerState
import expense.ledger.StoredSms
import expense.ledger.TransactionEvidence
import expense.merchants.AliasSource
import expense.parse.AccountKind
import expense.parse.BankMatcher
import expense.parse.BankProfile
import expense.parse.BankRegistry
import expense.parse.Direction
import expense.parse.Extraction
import expense.parse.FinancialEventType
import expense.parse.ParseAttempt
import expense.parse.ParseStatus
import expense.parse.SpendEffect
import expense.parse.TemplateExecution
import expense.parse.TemplateRunner
import expense.parse.TransactionCandidate
import expense.sms.BodyHash
import expense.sms.InboundSms
import expense.sms.SmsSource

/**
 * Bank-agnostic ingest. [registry] defaults to [BankRegistry.EMPTY].
 * Every institution, including ones not on this device, uses the same
 * discovery, classification, extraction, and validation pipeline.
 * SMS capture adapters and storage implementations sit outside this type.
 *
 * Routing is not re-derived here. The intelligence layer already decided
 * between the ledger, review, and silence, and this type only translates that
 * outcome into stored rows.
 */
class IngestPipeline(
    private val registry: BankRegistry = BankRegistry.EMPTY,
    private val ids: IdGenerator = UuidIdGenerator,
    intelligence: FinancialSmsIntelligence? = null,
) {
    private val suppliedIntelligence = intelligence
    private val senderEvidence = MutableSenderEvidenceLedger()
    private val intelligence: FinancialSmsIntelligence by lazy {
        suppliedIntelligence ?: FinancialSmsIntelligence.deterministic(
            senders = registry.profiles.map { RegisteredSender(it.id, it.displayName, it.senderIds) } +
                InstitutionBootstrap.records,
            senderEvidence = senderEvidence,
        )
    }

    fun preloadSenderEvidence(records: Collection<SenderEvidence>) = senderEvidence.preload(records)

    /** Records whose counts changed since the last [clearChangedSenderEvidence]. */
    fun changedSenderEvidence(): List<SenderEvidence> = senderEvidence.changed()

    fun clearChangedSenderEvidence() = senderEvidence.clearChanged()

    /** Card or account last-4 explicitly present in [body]. Null when the SMS does not state one. */
    fun explicitAccount(body: String): ExplicitAccount? {
        val instrument = PaymentInstruments.find(body) ?: return null
        return ExplicitAccount(mask = instrument.mask, kind = instrument.kind)
    }

    private val matcher = BankMatcher(registry)
    private val poster = LedgerPoster(ids)

    fun ingest(incoming: InboundSms, state: LedgerState = LedgerState.empty()): IngestResult {
        val sms = bounded(incoming)
        val assessment = intelligence.assess(SmsText(sms.sender, sms.body))
        observeSender(sms, assessment)
        if (DuplicateMatcher.providerReplay(state.messages, sms.providerMessageId) != null) {
            return IngestResult(
                state,
                status = null,
                attempt = null,
                alreadyIngested = true,
                eventType = assessment.event.eventType,
            )
        }
        val hash = BodyHash.sha256(sms.body)
        val bodyReplay = DuplicateMatcher.bodyReplay(state.messages, sms.sender, hash, sms.receivedAt)
        if (bodyReplay != null) {
            return IngestResult(
                state = reconcileReplay(state, bodyReplay, sms, hash),
                status = null,
                attempt = null,
                alreadyIngested = true,
                eventType = assessment.event.eventType,
            )
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
            eventType = decision.eventType,
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
            eventType = decision.eventType,
            spendEffect = decision.extraction?.candidates?.firstOrNull()?.spendEffect ?: SpendEffect.NONE,
            matchedProfile = matchedProfile,
            posted = posting,
        )
    }

    fun ingestAll(source: SmsSource, state: LedgerState = LedgerState.empty()): LedgerState {
        return source.messages().fold(state) { acc, sms -> ingest(sms, acc).state }
    }

    /**
     * Runs discovery, the semantic classifier, extraction, validation, and
     * routing for a body that is already stored. Does not read or write
     * ledger rows.
     */
    fun interpretStored(sender: String, body: String): StoredInterpretation {
        val sms = bounded(
            InboundSms(
                sender = sender,
                body = body,
                providerMessageId = null,
                receivedAt = java.time.Instant.EPOCH,
            ),
        )
        val assessment = intelligence.assess(SmsText(sms.sender, sms.body))
        observeSender(sms, assessment)
        val decision = interpret(sms, assessment)
        return StoredInterpretation(
            status = decision.status,
            retainBody = decision.retainBody,
            eventType = decision.eventType,
            profile = decision.profile,
            templateId = decision.templateId,
            extraction = decision.extraction ?: understood(assessment),
            error = decision.error,
        )
    }

    /**
     * What the message says, whether or not it may post.
     *
     * A transaction that is already in the ledger has had its institution
     * settled, so re-reading it is about the facts rather than about permission.
     * Carrying the extraction lets a row that predates a better reader gain the
     * card, the date, or the balance the message always stated, and it lets a
     * review item show what was understood.
     */
    private fun understood(assessment: ClassificationDecision): Extraction? {
        val event = assessment.event
        if (!event.moneyMovement || event.amount == null) return null
        return intelligenceExtraction(assessment)
    }

    fun postStored(
        state: LedgerState,
        message: StoredSms,
        profile: BankProfile,
        extraction: Extraction,
    ): LedgerState {
        return poster.post(state, message, profile, extraction)
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

    /**
     * Settles what to do with a message whose text this device already stored.
     *
     * A live broadcast is stored with no provider id, so the later inbox sync
     * reads the same text again. That is one message seen twice, not two
     * messages: the id is written onto the row that is already there, which
     * keeps one attempt and one transaction and still lets the provider-id
     * watermark advance past it.
     *
     * When the stored row already carries a provider id, the arrival is a
     * genuinely separate SMS with the same text. It is stored and linked as a
     * duplicate so the account holder can see both.
     */
    private fun reconcileReplay(
        state: LedgerState,
        stored: StoredSms,
        incoming: InboundSms,
        hash: String,
    ): LedgerState {
        val providerId = incoming.providerMessageId
        if (!providerId.isNullOrBlank() && stored.providerMessageId == null) {
            return state.copy(
                messages = state.messages.map { message ->
                    if (message.id == stored.id) message.copy(providerMessageId = providerId) else message
                },
            )
        }
        val duplicate = storedCopy(incoming, hash, retainBody = stored.body != null)
        return poster.attachDuplicateSms(state, stored.id, duplicate)
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

    /**
     * Translates one routing outcome into a stored reading.
     *
     * A bank template still wins when one matches, because an institution that
     * publishes its format is more precise than any general reader. Everything
     * else follows [RoutingOutcome]: a post becomes a ledger row, a hold becomes
     * a review item carrying the structural reason, and silence keeps no body.
     */
    private fun interpret(sms: InboundSms, assessment: ClassificationDecision): Interpretation {
        val eventType = assessment.event.eventType
        val matched = matcher.match(sms.sender)
        val profile = matched.singleOrNull()
        if (matched.size > 1) {
            val readable = assessment.routing.outcome != RoutingOutcome.IGNORE || anyTemplateReads(matched, sms)
            if (!readable) {
                return Interpretation(
                    status = ParseStatus.IGNORED_NOT_BANK,
                    retainBody = false,
                    eventType = eventType,
                )
            }
            return Interpretation(status = ParseStatus.AMBIGUOUS, retainBody = true, eventType = eventType)
        }
        if (profile != null) {
            when (val execution = TemplateRunner.execute(profile, sms)) {
                is TemplateExecution.ExtractorFailed -> return Interpretation(
                    status = ParseStatus.FAILED,
                    retainBody = true,
                    eventType = eventType,
                    profile = profile,
                    templateId = execution.template.id,
                    error = execution.reason,
                )
                is TemplateExecution.Extracted -> {
                    val agrees = execution.postable && !contradicts(assessment, execution.extraction)
                    return Interpretation(
                        status = if (agrees) ParseStatus.PARSED else ParseStatus.LOW_CONFIDENCE,
                        retainBody = true,
                        eventType = eventType,
                        profile = profile,
                        templateId = execution.template.id,
                        extraction = execution.extraction,
                    )
                }
                TemplateExecution.NoTemplateMatch -> Unit
            }
        }
        return when (assessment.routing.outcome) {
            RoutingOutcome.POST -> Interpretation(
                status = ParseStatus.PARSED,
                retainBody = true,
                eventType = eventType,
                profile = profile ?: verifiedProfile(assessment),
                extraction = intelligenceExtraction(assessment),
            )
            RoutingOutcome.REVIEW -> Interpretation(
                status = ParseStatus.UNSUPPORTED,
                retainBody = true,
                eventType = eventType,
                profile = profile,
                error = assessment.routing.holdReason,
            )
            RoutingOutcome.IGNORE -> Interpretation(
                status = ParseStatus.IGNORED_NOT_BANK,
                retainBody = false,
                eventType = eventType,
                profile = profile,
            )
        }
    }

    /**
     * Whether one of the candidate institutions publishes a format this body
     * fits. A published format that reads the message is evidence the message is
     * financial even when the general reader cannot tell, and the ambiguity is
     * then about which institution sent it rather than about what it says.
     */
    private fun anyTemplateReads(profiles: List<BankProfile>, sms: InboundSms): Boolean {
        return profiles.any { TemplateRunner.execute(it, sms) is TemplateExecution.Extracted }
    }

    /**
     * Records what this sender did, for every message rather than only the ones
     * that posted. Verification is earned from the whole history, so the
     * observations have to include the messages that were held or ignored.
     */
    private fun observeSender(sms: InboundSms, assessment: ClassificationDecision) {
        senderEvidence.observe(
            sender = sms.sender,
            eventType = assessment.event.eventType,
            moneyMovement = assessment.event.moneyMovement,
            instrument = assessment.entities.instrument,
        )
    }

    private fun contradicts(assessment: ClassificationDecision, extraction: Extraction): Boolean {
        val understood = assessment.entities.amount ?: return false
        val templated = extraction.candidates.firstOrNull()?.amount ?: return false
        return understood != templated
    }

    private fun verifiedProfile(assessment: ClassificationDecision): BankProfile {
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

    /**
     * One candidate carrying the separated facts: what the event was, what it
     * does to spending, the value of the event, and every other money value with
     * its role.
     */
    private fun intelligenceExtraction(assessment: ClassificationDecision): Extraction {
        val event = assessment.event
        return Extraction(
            confidence = event.confidence,
            candidates = listOf(
                TransactionCandidate(
                    eventType = event.eventType,
                    spendEffect = event.spendEffect,
                    amount = event.amount,
                    direction = event.direction ?: Direction.DEBIT,
                    merchantRaw = event.merchant,
                    occurredAt = event.occurredAt,
                    reference = event.reference,
                    accountMask = event.instrumentLast4,
                    accountKind = event.instrumentType,
                    balance = assessment.entities.amounts.firstOrNull { it.role.isBalance() }?.amount,
                    relatedAmounts = event.relatedAmounts,
                ),
            ),
        )
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
        val eventType: FinancialEventType,
        val profile: BankProfile? = null,
        val templateId: String? = null,
        val extraction: Extraction? = null,
        val error: String? = null,
    )
}

data class ExplicitAccount(
    val mask: String,
    val kind: AccountKind,
)
