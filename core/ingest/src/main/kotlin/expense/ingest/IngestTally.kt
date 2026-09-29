package expense.ingest

/**
 * What one scan actually did, counted honestly.
 *
 * Each field counts a different population and none of them is a proxy for
 * another. Retained SMS is not "financial transactions", a financial event is
 * not a ledger row, and a ledger row is not necessarily spending. Collapsing
 * them into one headline number is what made two screens disagree while both
 * claimed to be right.
 *
 * This value never carries message text.
 */
data class IngestTally(
    /** Inbox rows read, including replays and messages that were not financial. */
    val smsScanned: Int = 0,

    /** Messages the pipeline judged to be about money, movement or not. */
    val financialEvents: Int = 0,

    /** Financial events that became ledger transactions. */
    val postedTransactions: Int = 0,

    /** Completed money movements the pipeline could not finish alone. */
    val reviewItems: Int = 0,

    /** Posted transactions that add to spending. */
    val spendTransactions: Int = 0,

    /** Financial events that are neither a ledger row nor a review item. */
    val excludedFinancialEvents: Int = 0,
) {
    fun add(result: IngestResult): IngestTally {
        val nextScanned = smsScanned + 1
        if (result.alreadyIngested || result.status == null) {
            return copy(smsScanned = nextScanned)
        }
        val financial = result.financial
        val posted = result.posted
        val review = result.needsReview
        return copy(
            smsScanned = nextScanned,
            financialEvents = financialEvents + flag(financial),
            postedTransactions = postedTransactions + flag(posted),
            reviewItems = reviewItems + flag(review),
            spendTransactions = spendTransactions + flag(posted && result.countsTowardSpend),
            excludedFinancialEvents = excludedFinancialEvents + flag(financial && !posted && !review),
        )
    }

    operator fun plus(other: IngestTally): IngestTally {
        return IngestTally(
            smsScanned = smsScanned + other.smsScanned,
            financialEvents = financialEvents + other.financialEvents,
            postedTransactions = postedTransactions + other.postedTransactions,
            reviewItems = reviewItems + other.reviewItems,
            spendTransactions = spendTransactions + other.spendTransactions,
            excludedFinancialEvents = excludedFinancialEvents + other.excludedFinancialEvents,
        )
    }

    private fun flag(value: Boolean): Int = if (value) 1 else 0
}
