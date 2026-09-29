package expense.intelligence

import expense.money.DigitFold
import expense.parse.FinancialEventType

/**
 * Decides whether the event happened, whether money moved, and how strongly the
 * message supports either claim.
 *
 * The label alone is not enough. A predicted purchase is still only a claim, so
 * this detector asks the message for corroboration: a money value with a
 * currency, an instrument, a balance, a completion cue, or a confident model.
 * One signal is a [FinancialEvidence.WEAK] claim and is ignored. Two or more is
 * [FinancialEvidence.STRONG] and worth the account holder's attention even when
 * something else is missing.
 *
 * That is what keeps an appointment reminder or a delivery notice out of the
 * review queue without naming the sender that wrote it, and it is what lets a
 * genuinely incomplete bank message reach review instead of being dropped.
 *
 * The Stage 2 span model implements [EventStateDetector] with predicted flags
 * and this class is deleted rather than extended.
 */
class LexicalEventStateDetector(
    private val strongConfidence: Int = STRONG_CONFIDENCE,
) : EventStateDetector {
    override fun detect(
        message: SmsText,
        classification: Classification,
        entities: ExtractedEntities,
    ): EventState {
        val eventType = classification.eventType
        if (eventType == FinancialEventType.NOT_FINANCIAL) {
            return EventState(completed = false, moneyMovement = false, evidence = FinancialEvidence.NONE)
        }
        val folded = DigitFold.fold(message.body)
        val failed = failureCue.containsMatchIn(folded)
        val upcoming = futureCue.containsMatchIn(folded)
        val completionCue = completedCue.containsMatchIn(folded)
        val canMove = eventType.canMoveMoney()
        val completed = canMove && !failed && !upcoming
        val corroboration = listOf(
            classification.confidence >= strongConfidence && canMove,
            entities.amounts.any { it.role.isEventValue() },
            entities.instrument != null,
            entities.amounts.any { it.role.isBalance() },
            completionCue,
        ).count { it }
        val evidence = when {
            corroboration >= 2 -> FinancialEvidence.STRONG
            corroboration == 1 -> FinancialEvidence.WEAK
            else -> FinancialEvidence.NONE
        }
        return EventState(
            completed = completed,
            moneyMovement = completed && canMove,
            evidence = evidence,
        )
    }

    private companion object {
        const val STRONG_CONFIDENCE: Int = 80

        /** The event is announced rather than done. */
        val futureCue = Regex(
            """(?i)\bwill\s+be\b|\bwill\s+(?:be\s+)?(?:debited|charged|deducted|renewed|due)\b""" +
                """|\bscheduled\b|\bupcoming\b|\bis\s+due\b|\bdue\s+on\b|\breminder\b""" +
                """|سيتم|سوف|سيخصم|موعد\s*السداد|تذكير""",
        )

        /** The event did not complete. */
        val failureCue = Regex(
            """(?i)\b(?:declined|failed|rejected|unsuccessful|could\s+not\s+be|was\s+not\s+completed""" +
                """|insufficient)\b|\bnot\s+approved\b""" +
                """|فشل|لم\s*يتم|مرفوض|غير\s*كاف|رصيد\s*غير\s*كاف""",
        )

        /** The event is stated as done. */
        val completedCue = Regex(
            """(?i)\b(?:was|has\s+been|have\s+been)\s+(?:debited|credited|charged|deducted|paid|withdrawn""" +
                """|transferred|refunded|reversed|posted|completed|approved|renewed)\b""" +
                """|\b(?:successful(?:ly)?|completed|approved|posted|purchase\s+of|debit\s+of|credit\s+of)\b""" +
                """|\bpurchase\b.{0,20}\bat\b""" +
                """|تم\s*خصم|تم\s*شراء|تم\s*سحب|تم\s*تحويل|تم\s*سداد|تم\s*الدفع|تم\s*إضافة|تم\s*تجديد""" +
                """|تم\s*بنجاح|بنجاح|تمت""",
        )
    }
}
