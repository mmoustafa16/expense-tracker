package expense.android.ui.search

import expense.android.storage.SearchField
import expense.android.storage.SearchMatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchPagesTest {
    @Test
    fun `paging walks every match instead of keeping an arbitrary first page`() {
        val matches = (0 until 25).map { index ->
            SearchMatch(
                transactionId = null,
                smsId = "sms-$index",
                fields = setOf(SearchField.BODY),
                sortAt = index.toLong(),
            )
        }.sortedByDescending { it.sortAt }
        val first = SearchPages.window(matches, offset = 0)
        val second = SearchPages.window(matches, offset = 20)
        assertEquals(25, first.total)
        assertEquals(20, first.matches.size)
        assertEquals("sms-24", first.matches.first().smsId)
        assertEquals(5, second.matches.size)
        assertEquals("sms-0", second.matches.last().smsId)
        assertEquals(25, (first.matches + second.matches).map { it.smsId }.distinct().size)
        assertTrue(SearchPages.PAGE_SIZE <= 20)
    }
}
