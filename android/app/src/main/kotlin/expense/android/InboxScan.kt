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
)

/**
 * One inbox scan at a time. A second request while a scan is running, or after
 * it has finished in this process, does not start another scan.
 */
class InboxScanGate {
    private var active: Boolean = false
    private var finished: Boolean = false

    fun tryStart(): Boolean {
        if (active || finished) return false
        active = true
        return true
    }

    fun finish() {
        active = false
        finished = true
    }

    fun abandon() {
        active = false
    }
}

data class ScanMetric(
    val label: String,
    val value: String,
)

object InboxScanText {
    fun title(running: Boolean): String = if (running) "Scanning the inbox" else "Last inbox scan"

    fun metrics(tally: IngestTally): List<ScanMetric> {
        return listOf(
            ScanMetric("Scanned", count(tally.scanned)),
            ScanMetric("Financial", count(tally.financial)),
            ScanMetric("Needs review", count(tally.unsupported)),
        )
    }

    fun count(value: Int): String = "%,d".format(java.util.Locale.US, value)

    fun detail(tally: IngestTally): String {
        return "Matched ${count(tally.matchedProfile)} · Parsed ${count(tally.parsed)} · Posted ${count(tally.posted)}."
    }

    fun progress(tally: IngestTally, running: Boolean): String {
        val head = if (running) "Scanning the inbox." else "Inbox scan finished."
        return "$head Scanned ${tally.scanned}. Financial ${tally.financial}. " +
            "Matched ${tally.matchedProfile}. Unsupported ${tally.unsupported}. " +
            "Parsed ${tally.parsed}. Posted ${tally.posted}."
    }

    fun unmatchedNote(tally: IngestTally): String? {
        if (tally.unsupported == 0 || tally.posted > 0 || tally.matchedProfile > 0) return null
        return "Financial messages with no verified bank profile stay in Review. They are not added to the ledger."
    }
}
