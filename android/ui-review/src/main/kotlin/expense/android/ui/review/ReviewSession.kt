package expense.android.ui.review

import expense.android.storage.LedgerSession
import expense.ledger.LedgerState
import expense.ledger.ManualDraft

object ReviewSession {
    fun load(session: LedgerSession): LedgerState = session.load()

    fun rows(session: LedgerSession): List<ReviewRow> = ReviewQueue.rows(session.load())

    fun page(session: LedgerSession, offset: Int): ReviewPage = ReviewQueue.page(session.load(), offset)

    fun dismiss(session: LedgerSession, attemptId: String): List<ReviewRow> {
        return ReviewQueue.rows(session.dismissReview(attemptId))
    }

    fun dismissPage(session: LedgerSession, attemptId: String, offset: Int): ReviewPage {
        session.dismissReview(attemptId)
        return ReviewQueue.page(session.load(), offset)
    }

    fun post(session: LedgerSession, draft: ManualDraft): List<ReviewRow> {
        return ReviewQueue.rows(session.postManual(draft))
    }
}
