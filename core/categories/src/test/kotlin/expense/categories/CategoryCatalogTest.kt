package expense.categories

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CategoryCatalogTest {
    @Test
    fun `custom categories keep stable ids beside the seed tree`() {
        val added = CategoryCatalog.add(
            emptyList(),
            NewCategory(
                id = "pets",
                parentId = "shopping",
                nameEn = "Pets",
                nameAr = "حيوانات",
                slug = "pets",
                sortOrder = 61,
            ),
        )
        val changed = CategoryCatalog.update(
            added,
            "pets",
            CategoryChange(parentId = "other", nameEn = "Pet care", nameAr = "رعاية", sortOrder = 62),
        )
        val category = changed.single()
        assertEquals("pets", category.id)
        assertEquals("pets", category.slug)
        assertEquals("other", category.parentId)
        assertEquals(false, category.system)
        assertEquals("Pet care", category.nameEn)
    }

    @Test
    fun `seed ids and cycles are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CategoryCatalog.add(
                emptyList(),
                NewCategory("food", null, "Food", "طعام", "food-2", 1),
            )
        }
        val custom = CategoryCatalog.add(
            emptyList(),
            NewCategory("pets", null, "Pets", "حيوانات", "pets", 200),
        )
        val child = CategoryCatalog.add(
            custom,
            NewCategory("vet", "pets", "Vet", "بيطري", "vet", 201),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CategoryCatalog.update(child, "pets", CategoryChange("vet", "Pets", "حيوانات", 200))
        }
    }

    @Test
    fun `parent search expands to children`() {
        val custom = listOf(
            Category("pets", "shopping", "Pets", "حيوانات", "pets", 61, system = false),
        )
        val tree = CategorySeed.all + custom
        assertEquals(
            setOf("food", "groceries", "restaurants"),
            CategoryCatalog.descendants(setOf("food"), tree),
        )
        assertEquals(setOf("pets"), CategoryCatalog.descendants(setOf("pets"), tree))
    }
}
