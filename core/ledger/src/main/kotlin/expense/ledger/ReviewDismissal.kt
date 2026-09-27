package expense.ledger

import expense.parse.ParseAttempt

/**
 * A dismissed review item, keyed by the SMS identity that survives reparse.
 * The original body stays on the message.
 */
data class ReviewDismissal(
    val sender: String,
    val bodyHash: String,
    val receivedAt: java.time.Instant,
    val providerMessageId: String?,
) {
    fun matches(sms: StoredSms): Boolean {
        if (!providerMessageId.isNullOrBlank() && providerMessageId == sms.providerMessageId) {
            return true
        }
        return sender == sms.sender && bodyHash == sms.bodyHash && receivedAt == sms.receivedAt
    }
}

object ReviewDismissals {
    fun isDismissed(state: LedgerState, attempt: ParseAttempt): Boolean {
        val sms = state.messages.find { it.id == attempt.smsId } ?: return false
        return state.reviewDismissals.any { it.matches(sms) }
    }

    fun dismiss(state: LedgerState, attemptId: String): LedgerState {
        val attempt = state.attempts.find { it.id == attemptId } ?: return state
        if (!attempt.status.needsReview()) return state
        val sms = state.messages.find { it.id == attempt.smsId } ?: return state
        if (state.reviewDismissals.any { it.matches(sms) }) return state
        val dismissal = ReviewDismissal(
            sender = sms.sender,
            bodyHash = sms.bodyHash,
            receivedAt = sms.receivedAt,
            providerMessageId = sms.providerMessageId,
        )
        return state.copy(reviewDismissals = state.reviewDismissals + dismissal)
    }
}
