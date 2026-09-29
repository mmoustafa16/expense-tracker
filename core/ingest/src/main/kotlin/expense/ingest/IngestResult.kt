package expense.ingest

import expense.ledger.LedgerState
import expense.parse.BankProfile
import expense.parse.Extraction
import expense.parse.FinancialEventType
import expense.parse.ParseAttempt
import expense.parse.ParseStatus
import expense.parse.SpendEffect

data class IngestResult(
    val state: LedgerState,
    val status: ParseStatus?,
    val attempt: ParseAttempt?,
    val alreadyIngested: Boolean = false,
    val eventType: FinancialEventType = FinancialEventType.NOT_FINANCIAL,
    val spendEffect: SpendEffect = SpendEffect.NONE,
    val matchedProfile: Boolean = false,
    val posted: Boolean = false,
) {
    /** The message is about money. It may still never become a ledger row. */
    val financial: Boolean get() = eventType.isFinancialEvent()

    val needsReview: Boolean get() = status?.needsReview() == true

    val countsTowardSpend: Boolean get() = spendEffect.countsTowardSpend()
}

/**
 * Current-pipeline reading of one stored SMS. [posts] is true only when the
 * same decision would write a ledger row for a newly arrived message.
 */
data class StoredInterpretation(
    val status: ParseStatus,
    val retainBody: Boolean,
    val eventType: FinancialEventType = FinancialEventType.NOT_FINANCIAL,
    val profile: BankProfile? = null,
    val templateId: String? = null,
    val extraction: Extraction? = null,
    val error: String? = null,
) {
    val posts: Boolean
        get() = status == ParseStatus.PARSED && profile != null && extraction != null

    /** The single candidate a re-read produced, when the reading yielded one. */
    val candidate get() = extraction?.candidates?.singleOrNull()
}
