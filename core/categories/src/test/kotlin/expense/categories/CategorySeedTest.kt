package expense.categories

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CategorySeedTest {
    @Test
    fun `seed contains the approved expense tree`() {
        assertEquals(
            listOf(
                "food",
                "groceries",
                "restaurants",
                "transport",
                "fuel",
                "telecom",
                "utilities",
                "health",
                "shopping",
                "cash",
                "fees",
                "transfers",
                "other",
            ),
            CategorySeed.all.map { it.slug },
        )
    }

    @Test
    fun `children roll up to their parent`() {
        assertEquals(listOf("groceries", "food"), CategorySeed.rollup("groceries").map { it.slug })
        assertEquals(listOf("fuel", "transport"), CategorySeed.rollup("fuel").map { it.slug })
    }
}

class CategoryResolverTest {
    @Test
    fun `merchant rule beats a keyword rule`() {
        val rules = listOf(
            CategoryRule("k", MatchType.KEYWORD, "coffee", "restaurants", 10, RuleSource.SYSTEM),
            CategoryRule("m", MatchType.MERCHANT, "merchant-1", "groceries", 50, RuleSource.USER),
        )
        val assignment = CategoryResolver.resolve("merchant-1", "coffee shop", rules)
        assertEquals("groceries", assignment?.categoryId)
        assertEquals(CategorySource.USER, assignment?.source)
        assertEquals(true, assignment?.merchantSpecific)
    }
}
