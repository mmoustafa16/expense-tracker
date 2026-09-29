package expense.intelligence

/**
 * On-device SMS understanding. The steps are independent and replaceable:
 * [BankDiscovery], then [TransactionClassifier], then [FinancialEntityExtractor],
 * then [TransactionValidator], then a [ClassificationDecision].
 *
 * Classification and extraction run even when discovery returns unknown.
 * A ledger post needs one verified institution plus a validated high-confidence
 * transaction. The institution comes from an explicit sender record, public
 * metadata, or the sender channel itself. A handset number, an ambiguous
 * claim, and an incomplete transaction stay in review.
 *
 * The default classifier is the bundled on-device semantic model.
 * [replacing] swaps that model without changing discovery, validation, or the ledger.
 * This type does not call a cloud model or send the SMS anywhere.
 * Institutions are discovered data, not branches in this class.
 */
class FinancialSmsIntelligence(
    private val discovery: BankDiscovery,
    private val classifier: TransactionClassifier,
    private val extractor: FinancialEntityExtractor,
    private val validator: TransactionValidator,
) {
    fun assess(message: SmsText): ClassificationDecision {
        val discovered = discovery.discover(message)
        val classification = classifier.classify(message)
        val extracted = extractor.extract(message)
        val direction = if (classification.semantics != null) {
            classification.semantics.direction
        } else {
            directionFor(classification.type, message.body)
        }
        val entities = extracted.copy(direction = direction)
        val validation = validator.validate(message, classification, entities)
        val level = levelFor(classification, entities, validation, discovered)
        return ClassificationDecision(discovered, classification, entities, validation, level)
    }

    private fun levelFor(
        classification: Classification,
        entities: ExtractedEntities,
        validation: ValidationResult,
        discovered: BankDiscoveryResult,
    ): ConfidenceLevel {
        if (validation.ledgerForbidden && !validation.forcesReview) return ConfidenceLevel.LOW
        if (validation.forcesReview || classification.ambiguous) return ConfidenceLevel.MEDIUM
        val complete = validation.accepted &&
            classification.confidence >= HIGH_CONFIDENCE &&
            entities.amount != null &&
            entities.amountRole == AmountRole.TRANSACTION
        if (!complete) {
            return if (classification.type.isLedgerCandidate()) ConfidenceLevel.MEDIUM else ConfidenceLevel.LOW
        }
        if (discovered.verifiedInstitution == null) return ConfidenceLevel.MEDIUM
        return ConfidenceLevel.HIGH
    }

    private fun directionFor(type: TransactionClass, body: String): MoneyDirection? {
        if (!type.isLedgerCandidate()) return null
        val incoming = type == TransactionClass.TRANSFER &&
            incomingTransfer.containsMatchIn(body)
        return when {
            type == TransactionClass.REFUND || type == TransactionClass.REVERSAL || incoming -> MoneyDirection.CREDIT
            else -> MoneyDirection.DEBIT
        }
    }

    companion object {
        const val HIGH_CONFIDENCE: Int = 80

        private val incomingTransfer = Regex("""(?i)\b(received|incoming)\b|\btransfer\s+in\b""")

        fun deterministic(
            senders: List<RegisteredSender> = InstitutionBootstrap.records,
            userConfirmed: List<UserConfirmedSender> = emptyList(),
            evidence: List<LocalInstitutionEvidence> = emptyList(),
        ): FinancialSmsIntelligence {
            return replacing(
                classifier = SemanticTransactionClassifier.bundled(),
                extractor = DeterministicEntityExtractor(),
                senders = senders,
                userConfirmed = userConfirmed,
                evidence = evidence,
            )
        }

        /**
         * Same discovery, validation, and ledger rules with a different
         * classifier and extractor. An on-device model plugs in here.
         * There is no network call.
         */
        fun replacing(
            classifier: TransactionClassifier,
            extractor: FinancialEntityExtractor,
            senders: List<RegisteredSender> = InstitutionBootstrap.records,
            userConfirmed: List<UserConfirmedSender> = emptyList(),
            evidence: List<LocalInstitutionEvidence> = emptyList(),
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
                        InstitutionalSenderDiscovery(),
                    ),
                ),
                classifier = classifier,
                extractor = extractor,
                validator = DeterministicTransactionValidator(),
            )
        }
    }
}

/**
 * Why a ledger candidate was not posted. Null when the message is not a
 * transaction or when it is postable. The labels are structural, not bank names.
 */
fun ClassificationDecision.reviewHold(): String? {
    if (!classification.type.isLedgerCandidate() || postable) return null
    if (classification.ambiguous) return "ambiguous_meaning"
    if (entities.amountRole == AmountRole.AMBIGUOUS) return "ambiguous_amount"
    if (entities.amount == null || entities.amountRole != AmountRole.TRANSACTION) return "amount_missing"
    if (!validation.accepted || validation.forcesReview) return "validation"
    if (classification.confidence < FinancialSmsIntelligence.HIGH_CONFIDENCE) return "low_confidence"
    return when (discovery.status) {
        DiscoveryStatus.AMBIGUOUS -> "ambiguous_institution"
        DiscoveryStatus.UNKNOWN -> "unknown_institution"
        DiscoveryStatus.KNOWN ->
            if (discovery.verifiedInstitution == null) "unverified_institution" else "not_postable"
    }
}
