package expense.android.ui.review

import expense.android.storage.LedgerSession
import expense.ledger.LedgerState
import expense.ledger.ManualDraft

object ReviewSession {
    fun load(session: LedgerSession): LedgerState = session.load()

    fun rows(session: LedgerSession): List<ReviewRow> = ReviewQueue.rows(session.load())

    fun peek(session: LedgerSession, offset: Int): ReviewPage? {
        return session.peekReview(offset, ReviewQueue.PAGE_SIZE)?.let(::toPage)
    }

    fun page(session: LedgerSession, offset: Int): ReviewPage {
        return toPage(session.reviewWindow(offset, ReviewQueue.PAGE_SIZE))
    }

    private fun toPage(window: expense.android.storage.ReviewWindow): ReviewPage {
        return ReviewPage(
            rows = window.rows.map { row ->
                ReviewRow(
                    attemptId = row.attemptId,
                    smsId = row.smsId,
                    status = row.status,
                    sender = row.sender,
                    receivedAt = row.receivedAt,
                    body = row.body,
                    pipelineVersion = row.pipelineVersion,
                    reason = row.holdReason,
                )
            },
            offset = window.offset,
            total = window.total,
        )
    }

    fun dismiss(session: LedgerSession, attemptId: String): List<ReviewRow> {
        return ReviewQueue.rows(session.dismissReview(attemptId))
    }

    fun dismissPage(session: LedgerSession, attemptId: String, offset: Int): ReviewPage {
        session.dismissReview(attemptId)
        return page(session, offset)
    }

    fun post(session: LedgerSession, draft: ManualDraft): List<ReviewRow> {
        return ReviewQueue.rows(session.postManual(draft))
    }
}
