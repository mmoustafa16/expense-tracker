package expense.android.ui.common

import expense.categories.Category
import expense.categories.CategoryCatalog
import expense.categories.CategorySeed

/**
 * The visible tree always starts from [CategorySeed] and then adds custom rows.
 * Seed ids stay stable. Custom ids are whatever the ledger already stored.
 */
object CategoryOptions {
    fun tree(custom: List<Category>): List<Category> {
        return (CategorySeed.all + custom).sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.id })
    }

    fun find(tree: List<Category>, id: String?): Category? {
        if (id.isNullOrBlank()) return null
        return tree.find { it.id == id }
    }

    fun descendants(tree: List<Category>, categoryId: String): Set<String> {
        return CategoryCatalog.descendants(setOf(categoryId), tree)
    }

    fun label(category: Category): String = category.nameEn

    fun canEdit(category: Category): Boolean {
        return !category.system && CategorySeed.bySlug(category.id) == null && CategorySeed.all.none { it.id == category.id }
    }
}
