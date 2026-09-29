package expense.android.ui.search

import expense.android.storage.LedgerSession

data class SearchPage(
    val hits: List<SearchHitView>,
    val offset: Int,
    val total: Int,
)

object SearchSession {
    fun query(session: LedgerSession, text: String): List<SearchHitView> = page(session, text, 0).hits

    fun page(session: LedgerSession, text: String, offset: Int): SearchPage {
        val matches = session.search(text)
        val window = SearchPages.window(matches, offset)
        if (window.matches.isEmpty()) {
            return SearchPage(emptyList(), window.offset, window.total)
        }
        val shown = SearchPresentation.present(session.snapshot(window.matches), window.matches)
        return SearchPage(shown, window.offset, window.total)
    }
}
