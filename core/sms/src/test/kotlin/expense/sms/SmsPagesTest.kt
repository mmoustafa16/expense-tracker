package expense.sms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmsPagesTest {
    @Test
    fun `a large inbox is delivered in bounded pages`() {
        val total = 1_000
        val items = (0 until total).map { "sms-$it" }
        val sizes = mutableListOf<Int>()
        var seen = 0
        SmsPages.consume(items.iterator(), SmsPages.DEFAULT_PAGE_SIZE) { page ->
            assertTrue(page.size <= SmsPages.DEFAULT_PAGE_SIZE)
            assertEquals(items.subList(seen, seen + page.size), page)
            sizes += page.size
            seen += page.size
        }
        assertEquals(total, seen)
        assertEquals(25, sizes.size)
        assertTrue(sizes.all { it == SmsPages.DEFAULT_PAGE_SIZE })
        assertTrue(SmsPages.DEFAULT_PAGE_SIZE <= 40)
    }

    @Test
    fun `a short remainder is its own page and an empty source delivers nothing`() {
        val sizes = mutableListOf<Int>()
        SmsPages.consume((0 until 95).iterator(), 40) { sizes += it.size }
        assertEquals(listOf(40, 40, 15), sizes)
        val none = mutableListOf<List<Int>>()
        SmsPages.consume(emptyList<Int>().iterator(), 40) { none += it }
        assertTrue(none.isEmpty())
    }
}
