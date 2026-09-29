package expense.intelligence

import expense.parse.AmountResolution

/**
 * Turns one understood message into an outcome.
 *
 * Routing is the only place that decides between the ledger, the review queue,
 * and silence, and it reads the separated facts rather than re-deriving them:
 * the semantic event type, whether the event completed, whether money moved,
 * how strong the evidence is, whether the amount resolved, and whether an
 * institution is verified.
 *
 * Two rules matter most and neither existed before:
 *
 *  - A message that is not a financial event is ignored. It is never held.
 *  - A missing amount is only a reason to ask the account holder when the
 *    message otherwise shows a completed money movement with strong evidence.
 *    Absent that, silence is the honest outcome.
 */
object FinancialRouting {
    const val HIGH_CONFIDENCE: Int = 80

    fun route(
        classification: Classification,
        entities: ExtractedEntities,
        state: EventState,
        validation: ValidationResult,
        discovery: BankDiscoveryResult,
    ): Routing {
        if (!classification.eventType.isFinancialEvent()) return ignored()
        if (state.evidence == FinancialEvidence.NONE) return ignored()
        if (!state.completed || !state.moneyMovement) return ignored()
        if (state.evidence != FinancialEvidence.STRONG) return ignored()
        if (classification.ambiguous) return review("ambiguous_meaning")
        if (entities.resolution == AmountResolution.AMBIGUOUS) return review("ambiguous_amount")
        if (entities.resolution == AmountResolution.MISSING || entities.amount == null) {
            return review("amount_missing")
        }
        if (!validation.accepted) return review("validation")
        if (classification.confidence < HIGH_CONFIDENCE) return review("low_confidence")
        return when (discovery.status) {
            DiscoveryStatus.AMBIGUOUS -> review("ambiguous_institution")
            DiscoveryStatus.UNKNOWN -> review("unknown_institution")
            DiscoveryStatus.KNOWN ->
                if (discovery.verifiedInstitution == null) {
                    review("unverified_institution")
                } else {
                    Routing(RoutingOutcome.POST, holdReason = null)
                }
        }
    }

    private fun ignored() = Routing(RoutingOutcome.IGNORE, holdReason = null)

    private fun review(reason: String) = Routing(RoutingOutcome.REVIEW, holdReason = reason)
}
