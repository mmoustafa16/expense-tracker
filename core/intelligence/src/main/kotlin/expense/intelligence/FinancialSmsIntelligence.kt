package expense.intelligence

/**
 * On-device SMS understanding. The steps are independent and replaceable:
 * [BankDiscovery], then [TransactionClassifier], then [FinancialEntityExtractor],
 * then [TransactionValidator], then a [ClassificationDecision].
 *
 * Classification and extraction run even when discovery returns unknown.
 * A ledger post still requires a [VerifiedSenderRegistry] hit plus a validated
 * high-confidence transaction. Evidence, metadata, and a user confirmation
 * can name an institution, and an unknown or ambiguous result stays in review.
 *
 * A future small on-device model can replace discovery, the classifier, or
 * the extractor. This type does not call a cloud model or send the SMS anywhere.
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
        val entities = extracted.copy(direction = directionFor(classification.type, message.body))
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
        if (needsCounterparty(classification.type) && entities.merchant.isNullOrBlank() && entities.accountMask.isNullOrBlank()) {
            return ConfidenceLevel.MEDIUM
        }
        if (discovered.verifiedInstitution == null) return ConfidenceLevel.MEDIUM
        return ConfidenceLevel.HIGH
    }

    private fun needsCounterparty(type: TransactionClass): Boolean {
        return type == TransactionClass.CARD_PURCHASE ||
            type == TransactionClass.REFUND ||
            type == TransactionClass.PAYMENT
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
            senders: List<RegisteredSender> = emptyList(),
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
                    ),
                ),
                classifier = DeterministicTransactionClassifier(),
                extractor = DeterministicEntityExtractor(),
                validator = DeterministicTransactionValidator(),
            )
        }
    }
}
