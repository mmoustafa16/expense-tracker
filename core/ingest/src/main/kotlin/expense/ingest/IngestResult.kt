package expense.ingest

import expense.ledger.LedgerState
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
