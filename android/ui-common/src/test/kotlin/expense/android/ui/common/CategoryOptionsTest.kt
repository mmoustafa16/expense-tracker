package expense.android.ui.common

import expense.categories.Category
import expense.categories.CategorySeed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CategoryOptionsTest {
    @Test
    fun `the visible tree starts from the seed and keeps custom ids`() {
        val custom = Category("pets", "shopping", "Pets", "حيوانات", "pets", 61, system = false)
        val tree = CategoryOptions.tree(listOf(custom))
        assertEquals(CategorySeed.all.map { it.id }.toSet() + "pets", tree.map { it.id }.toSet())
        assertTrue(tree.indexOfFirst { it.id == "pets" } > tree.indexOfFirst { it.id == "shopping" })
        assertEquals(setOf("shopping", "pets"), CategoryOptions.descendants(tree, "shopping"))
        assertFalse(CategoryOptions.canEdit(CategorySeed.all.first()))
        assertTrue(CategoryOptions.canEdit(custom))
    }
}

class MoneyFormatTest {
    @Test
    fun `signed totals keep a separate currency code`() {
        assertEquals("20.00 EGP", MoneyFormat.format(2000, expense.money.Currency.EGP))
        assertEquals("-1.50 USD", MoneyFormat.format(-150, expense.money.Currency.USD))
        assertEquals("7 JPY", MoneyFormat.format(7, expense.money.Currency("JPY", 0)))
    }
}
