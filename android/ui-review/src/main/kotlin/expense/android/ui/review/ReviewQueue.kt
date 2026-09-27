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

object ReviewQueue {
    fun rows(state: LedgerState): List<ReviewRow> {
        return state.reviewQueue().map { attempt ->
            val sms = state.messages.find { it.id == attempt.smsId }
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
