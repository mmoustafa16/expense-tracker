package expense.android.ui.analytics

import expense.android.storage.LedgerSession
import expense.ledger.LedgerState

object AnalyticsSession {
    fun load(session: LedgerSession): LedgerState = session.load()

    fun report(session: LedgerSession, slice: AnalyticsSlice): AnalyticsReport {
        return SpendAnalytics.report(session.load(), slice)
    }
}
