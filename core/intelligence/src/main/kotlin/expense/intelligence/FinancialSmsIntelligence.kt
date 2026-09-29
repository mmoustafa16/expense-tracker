package expense.intelligence

import expense.parse.Direction
import expense.parse.EventAmounts
import expense.parse.FinancialEventType
import expense.parse.SpendEffect

/**
 * On-device SMS understanding. Every step is an interface with one job, so any
 * one of them can be replaced without touching the others:
 *
 *  1. [BankDiscovery] names candidate institutions from the sender.
 *  2. [TransactionClassifier] gives the semantic event type.
 *  3. [FinancialEntityExtractor] copies out amounts, roles, and instruments.
 *  4. [EventStateDetector] says whether the event completed and moved money.
 *  5. [TransactionValidator] checks the entities against the message.
 *  6. [EventTypeRefiner] narrows the event type using what was extracted.
 *  7. [FinancialRouting] chooses the ledger, review, or silence.
 *
 * Classification and extraction run even when discovery returns unknown, so the
 * outcome of an unrecognized institution is a review item rather than silence.
 *
 * [replacing] swaps the model without changing discovery, validation, routing,
 * or the ledger. Nothing here calls a network service or logs the body.
 * Institutions are discovered data, not branches in this class.
 */
class FinancialSmsIntelligence(
    private val discovery: BankDiscovery,
    private val classifier: TransactionClassifier,
    private val extractor: FinancialEntityExtractor,
    private val validator: TransactionValidator,
    private val stateDetector: EventStateDetector = LexicalEventStateDetector(),
    private val refiner: EventTypeRefiner = InstrumentAwareEventTypeRefiner(),
) {
    fun assess(message: SmsText): ClassificationDecision {
        val discovered = discovery.discover(message)
        val predicted = classifier.classify(message)
        val extracted = extractor.extract(message)
        val refined = refiner.refine(message, predicted, extracted)
        val entities = withEventAmount(extracted, refined.eventType)
            .copy(direction = directionFor(refined, extracted))
        val state = stateDetector.detect(message, refined, entities)
        val validation = validator.validate(message, entities)
        val routing = FinancialRouting.route(refined, entities, state, validation, discovered)
        val event = eventOf(refined, entities, state, discovered)
        return ClassificationDecision(
            discovery = discovered,
            classification = refined,
            entities = entities,
            state = state,
            validation = validation,
            event = event,
            routing = routing,
        )
    }

    /**
     * Re-reads the amounts once the event type is known. The extractor names
     * every value without knowing what happened; only here is it settled which
     * of those names carries the value of this event.
     */
    private fun withEventAmount(
        entities: ExtractedEntities,
        eventType: FinancialEventType,
    ): ExtractedEntities {
        val roles = eventType.valueRoles()
        return entities.copy(
            valueRoles = roles,
            resolution = EventAmounts.resolve(entities.amounts, roles),
        )
    }

    private fun eventOf(
        classification: Classification,
        entities: ExtractedEntities,
        state: EventState,
        discovered: BankDiscoveryResult,
    ): FinancialEvent {
        val institution = discovered.verifiedInstitution ?: discovered.candidates.firstOrNull()
        return FinancialEvent(
            eventType = classification.eventType,
            completed = state.completed,
            moneyMovement = state.moneyMovement,
            direction = entities.direction,
            spendEffect = spendEffectOf(classification.eventType, entities),
            amount = entities.amount,
            currency = entities.currency,
            relatedAmounts = entities.relatedAmounts,
            merchant = entities.merchant,
            institutionId = institution?.institutionId,
            institutionName = institution?.displayName,
            instrumentType = entities.instrument?.kind,
            instrumentLast4 = entities.instrument?.mask,
            occurredAt = entities.occurredAt,
            reference = entities.reference,
            evidence = state.evidence,
            confidence = classification.confidence,
        )
    }

    /**
     * A transfer whose destination is the holder's own credit instrument settles
     * a liability instead of leaving the household, so it is not spending.
     * Everything else takes the default for its event type.
     */
    private fun spendEffectOf(
        eventType: FinancialEventType,
        entities: ExtractedEntities,
    ): SpendEffect {
        val default = eventType.defaultSpendEffect()
        if (eventType != FinancialEventType.BANK_TRANSFER) return default
        return if (entities.instrument?.kind?.isCredit() == true) {
            SpendEffect.LIABILITY_SETTLEMENT
        } else {
            default
        }
    }

    private fun directionFor(classification: Classification, entities: ExtractedEntities): Direction? {
        entities.direction?.let { return it }
        classification.semantics?.direction?.let { return it }
        if (!classification.eventType.canMoveMoney()) return null
        return when (classification.eventType) {
            FinancialEventType.REFUND, FinancialEventType.REVERSAL, FinancialEventType.INCOME -> Direction.CREDIT
            else -> Direction.DEBIT
        }
    }

    companion object {
        const val HIGH_CONFIDENCE: Int = FinancialRouting.HIGH_CONFIDENCE

        fun deterministic(
            senders: List<RegisteredSender> = InstitutionBootstrap.records,
            userConfirmed: List<UserConfirmedSender> = emptyList(),
            evidence: List<LocalInstitutionEvidence> = emptyList(),
            senderEvidence: SenderEvidenceSource = SenderEvidenceSource.EMPTY,
        ): FinancialSmsIntelligence {
            return replacing(
                classifier = SemanticTransactionClassifier.bundled(),
                extractor = DeterministicEntityExtractor(),
                senders = senders,
                userConfirmed = userConfirmed,
                evidence = evidence,
                senderEvidence = senderEvidence,
            )
        }

        /**
         * Same discovery, validation, routing, and ledger rules with a different
         * classifier and extractor. An on-device model plugs in here.
         * There is no network call.
         */
        fun replacing(
            classifier: TransactionClassifier,
            extractor: FinancialEntityExtractor,
            senders: List<RegisteredSender> = InstitutionBootstrap.records,
            userConfirmed: List<UserConfirmedSender> = emptyList(),
            evidence: List<LocalInstitutionEvidence> = emptyList(),
            senderEvidence: SenderEvidenceSource = SenderEvidenceSource.EMPTY,
            stateDetector: EventStateDetector = LexicalEventStateDetector(),
        ): FinancialSmsIntelligence {
            return FinancialSmsIntelligence(
                discovery = CompositeBankDiscovery(
                    sources = listOf(
                        VerifiedSenderRegistry(senders),
                        EvidenceBackedDiscovery(StructuralBankEvidenceCollector(), evidence),
                        PublicBankMetadata(),
                        UserConfirmedSenders(userConfirmed),
                        OnDeviceModelDiscovery(),
                        LocalLearnedPatterns(),
                        InstitutionalSenderDiscovery(senderEvidence),
                    ),
                ),
                classifier = classifier,
                extractor = extractor,
                validator = DeterministicTransactionValidator(),
                stateDetector = stateDetector,
            )
        }
    }
}

/**
 * Narrows a predicted event type using what the message stated.
 *
 * The classifier answers "what kind of message is this" from wording alone. Some
 * distinctions the ledger needs are not in the wording but in the structure: a
 * payment aimed at a credit instrument is a card bill being settled, not a
 * purchase, and counting it as spending double-counts every purchase it covers.
 */
fun interface EventTypeRefiner {
    fun refine(
        message: SmsText,
        classification: Classification,
        entities: ExtractedEntities,
    ): Classification
}

/**
 * Recognizes a credit-card bill payment from the instrument the message names
 * and the settlement vocabulary around it. Both are properties of the message,
 * not of the sender, so any issuer writing the same fact is read the same way.
 *
 * How much the message has to state depends on how far the prediction already
 * is from a settlement. When the label is a payment or a transfer, a credit
 * instrument as the destination is enough. When the label is something else the
 * message has to say all three things a settlement says and a purchase does not:
 * money was repaid, the destination is a credit instrument, and there is no
 * payee other than that instrument.
 */
class InstrumentAwareEventTypeRefiner : EventTypeRefiner {
    override fun refine(
        message: SmsText,
        classification: Classification,
        entities: ExtractedEntities,
    ): Classification {
        val eventType = classification.eventType
        if (eventType == FinancialEventType.CREDIT_CARD_PAYMENT || !eventType.canMoveMoney()) {
            return classification
        }
        val towardCredit = entities.instrument?.kind?.isCredit() == true
        val settlement = when (eventType) {
            FinancialEventType.BILL_PAYMENT, FinancialEventType.BANK_TRANSFER ->
                towardCredit || settlementCue.containsMatchIn(message.body)
            else ->
                towardCredit && entities.merchant == null && repaymentCue.containsMatchIn(message.body)
        }
        if (!settlement) return classification
        return classification.copy(eventType = FinancialEventType.CREDIT_CARD_PAYMENT)
    }

    private companion object {
        /** The message says outright which liability was settled. */
        val settlementCue = Regex(
            """(?i)\b(?:credit\s*card\s*(?:bill|payment|dues?)|card\s*payment\s*received""" +
                """|payment\s*(?:to|towards|toward)\s*(?:your\s*)?credit\s*card""" +
                """|outstanding\s*(?:balance|dues?)\s*(?:paid|settled))\b""" +
                """|سداد\s*(?:مديونية|بطاقة)|سداد\s*البطاقة|دفع\s*بطاقة\s*الائتمان""",
        )

        /** Money going back rather than out: repayment vocabulary in either language. */
        val repaymentCue = Regex(
            """(?i)\b(?:repaid|repayment|settled|settlement|paid\s*(?:off|towards?|to)""" +
                """|payment\s*(?:of|received|posted)|received\s*with\s*thanks)\b""" +
                """|سداد|سدّد|سدد|تسوية|تسديد""",
        )
    }
}

/**
 * Why a completed money movement was not posted. Null when it posted or when the
 * message was not a completed movement at all. The labels are structural and
 * never name an institution.
 */
fun ClassificationDecision.reviewHold(): String? = routing.holdReason
