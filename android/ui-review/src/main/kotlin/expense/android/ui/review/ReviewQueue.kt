package expense.android.ui.review

import expense.ledger.LedgerState
import expense.parse.ParseStatus
import java.time.Instant

data class ReviewRow(
    val attemptId: String,
    val smsId: String,
    val status: ParseStatus,
    val sender: String,
    val receivedAt: Instant,
    val body: String?,
    val pipelineVersion: String,
)

data class ReviewPage(
    val rows: List<ReviewRow>,
    val offset: Int,
    val total: Int,
)

object ReviewQueue {
    const val PAGE_SIZE: Int = 20

    fun rows(state: LedgerState): List<ReviewRow> = page(state, offset = 0, pageSize = Int.MAX_VALUE).rows

    /**
     * One window of the review queue. Only this window's SMS bodies are copied
     * into the returned rows.
     */
    fun page(state: LedgerState, offset: Int, pageSize: Int = PAGE_SIZE): ReviewPage {
        require(pageSize > 0)
        val queued = state.reviewQueue()
        val start = pageStart(queued.size, offset, pageSize)
        val slice = queued.drop(start).take(pageSize)
        val messages = state.messages.associateBy { it.id }
        val rows = slice.map { attempt ->
            val sms = messages[attempt.smsId]
            ReviewRow(
                attemptId = attempt.id,
                smsId = attempt.smsId,
                status = attempt.status,
                sender = sms?.sender.orEmpty(),
                receivedAt = sms?.receivedAt ?: Instant.EPOCH,
                body = sms?.body,
                pipelineVersion = attempt.pipelineVersion,
            )
        }
        return ReviewPage(rows = rows, offset = start, total = queued.size)
    }

    internal fun pageStart(total: Int, offset: Int, pageSize: Int): Int {
        if (total <= 0) return 0
        val requested = offset.coerceAtLeast(0)
        if (requested < total) return requested
        return ((total - 1) / pageSize) * pageSize
    }

    fun statusLabel(status: ParseStatus): String {
        return when (status) {
            ParseStatus.UNSUPPORTED -> "Unsupported"
            ParseStatus.LOW_CONFIDENCE -> "Low confidence"
            ParseStatus.FAILED -> "Failed"
            ParseStatus.AMBIGUOUS -> "Ambiguous"
            ParseStatus.IGNORED_NOT_BANK -> "Ignored"
            ParseStatus.PARSED -> "Parsed"
        }
    }
}
