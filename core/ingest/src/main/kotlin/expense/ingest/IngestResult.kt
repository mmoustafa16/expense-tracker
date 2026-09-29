package expense.ingest

import expense.ledger.LedgerState
import expense.parse.BankProfile
import expense.parse.Extraction
import expense.parse.ParseAttempt
import expense.parse.ParseStatus

data class IngestResult(
    val state: LedgerState,
    val status: ParseStatus?,
    val attempt: ParseAttempt?,
    val alreadyIngested: Boolean = false,
    val financial: Boolean = false,
    val matchedProfile: Boolean = false,
    val posted: Boolean = false,
)

/**
 * Current-pipeline reading of one stored SMS. [posts] is true only when the
 * same decision would write a ledger row for a newly arrived message.
 */
data class StoredInterpretation(
    val status: ParseStatus,
    val retainBody: Boolean,
    val profile: BankProfile? = null,
    val templateId: String? = null,
    val extraction: Extraction? = null,
    val error: String? = null,
) {
    val posts: Boolean
        get() = status == ParseStatus.PARSED && profile != null && extraction != null
}
