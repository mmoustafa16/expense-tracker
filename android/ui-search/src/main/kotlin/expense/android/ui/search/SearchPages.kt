package expense.android.ui.search

import expense.android.storage.SearchMatch

data class SearchWindow(
    val matches: List<SearchMatch>,
    val offset: Int,
    val total: Int,
)

object SearchPages {
    const val PAGE_SIZE: Int = 20

    fun window(matches: List<SearchMatch>, offset: Int, pageSize: Int = PAGE_SIZE): SearchWindow {
        require(pageSize > 0)
        val start = if (matches.isEmpty()) {
            0
        } else {
            val requested = offset.coerceAtLeast(0)
            if (requested < matches.size) requested else ((matches.size - 1) / pageSize) * pageSize
        }
        return SearchWindow(
            matches = matches.drop(start).take(pageSize),
            offset = start,
            total = matches.size,
        )
    }
}
