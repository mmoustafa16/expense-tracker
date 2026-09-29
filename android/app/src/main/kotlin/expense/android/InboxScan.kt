package expense.android

import expense.ingest.IngestTally

enum class InboxScanPhase {
    IDLE,
    RUNNING,
    FINISHED,
}

data class InboxScan(
    val phase: InboxScanPhase = InboxScanPhase.IDLE,
    val tally: IngestTally = IngestTally(),
    val generation: Int = 0,
)

/**
 * One inbox sync at a time. A finished sync can run again so messages that
 * arrived after the previous cursor are ingested. A second call while a sync
 * is still running does not start another one.
 */
class InboxScanGate {
    private var active: Boolean = false

    fun tryStart(): Boolean {
        if (active) return false
        active = true
        return true
    }

    fun finish() {
        active = false
    }

    fun abandon() {
        active = false
    }
}

data class ScanMetric(
    val label: String,
    val value: String,
)

/**
 * Names each count by the population it measures.
 *
 * Every label here states what was counted. An SMS this device kept is not a
 * financial event, a financial event is not a ledger row, and a ledger row is
 * not necessarily spending, so the screen shows four numbers that are allowed to
 * differ instead of one that has to be wrong.
 */
object InboxScanText {
    fun title(running: Boolean): String = if (running) "Scanning the inbox" else "Last inbox scan"

    fun metrics(tally: IngestTally): List<ScanMetric> {
        return listOf(
            ScanMetric("SMS scanned", count(tally.smsScanned)),
            ScanMetric("Financial events", count(tally.financialEvents)),
            ScanMetric("Posted", count(tally.postedTransactions)),
            ScanMetric("Needs review", count(tally.reviewItems)),
        )
    }

    fun count(value: Int): String = "%,d".format(java.util.Locale.US, value)

    fun detail(tally: IngestTally): String {
        return "Spend transactions ${count(tally.spendTransactions)} · " +
            "Financial events not in the ledger ${count(tally.excludedFinancialEvents)}."
    }

    fun progress(tally: IngestTally, running: Boolean): String {
        val head = if (running) "Scanning the inbox." else "Inbox scan finished."
        return "$head SMS scanned ${tally.smsScanned}. Financial events ${tally.financialEvents}. " +
            "Posted transactions ${tally.postedTransactions}. Needs review ${tally.reviewItems}. " +
            "Spend transactions ${tally.spendTransactions}. " +
            "Financial events not in the ledger ${tally.excludedFinancialEvents}."
    }

    fun unmatchedNote(tally: IngestTally): String? {
        if (tally.reviewItems == 0 || tally.postedTransactions > 0) return null
        return "Financial messages from a sender this device has not verified stay in Review. " +
            "They are not added to the ledger."
    }
}
