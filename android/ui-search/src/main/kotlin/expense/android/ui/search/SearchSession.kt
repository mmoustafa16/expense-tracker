package expense.android.ui.search

import expense.android.storage.LedgerSession

object SearchSession {
    fun query(session: LedgerSession, text: String): List<SearchHitView> {
        val matches = session.search(text).take(SearchPresentation.MAX_HITS)
        if (matches.isEmpty()) return emptyList()
        return SearchPresentation.present(session.load(), matches)
    }
}
