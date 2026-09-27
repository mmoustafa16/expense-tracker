package expense.android.ui.ledger

import expense.android.ui.common.CategoryOptions
import expense.android.ui.common.UiResult
import expense.categories.Category
import expense.categories.CategoryCatalog
import expense.categories.CategoryChange
import expense.categories.NewCategory
import expense.ledger.Correction
import expense.ledger.CorrectionField
import expense.ledger.Transaction
import java.time.Instant

/**
 * Category and merchant corrections are ledger overlays. They do not carry a
 * bank profile or a parser template.
 */
object LedgerCorrections {
    fun category(
        transaction: Transaction,
        categoryId: String,
        categories: List<Category>,
        applyForward: Boolean,
        correctionId: String,
        createdAt: Instant,
    ): UiResult<Correction> {
        val canonical = CategoryCatalog.canonicalId(categoryId, categories)
            ?: return UiResult.Rejected("Choose a category from the ledger.")
        return UiResult.Ready(
            Correction(
                id = correctionId,
                dedupKey = transaction.dedupKey,
                field = CorrectionField.CATEGORY,
                previousValue = transaction.categoryId,
                updatedValue = canonical,
                applyForward = applyForward,
                createdAt = createdAt,
            ),
        )
    }

    fun merchant(
        transaction: Transaction,
        merchantName: String,
        previousName: String?,
        applyForward: Boolean,
        correctionId: String,
        createdAt: Instant,
    ): UiResult<Correction> {
        val name = merchantName.trim()
        if (name.isEmpty()) return UiResult.Rejected("Enter a merchant name.")
        return UiResult.Ready(
            Correction(
                id = correctionId,
                dedupKey = transaction.dedupKey,
                field = CorrectionField.MERCHANT,
                previousValue = previousName,
                updatedValue = name,
                applyForward = applyForward,
                createdAt = createdAt,
            ),
        )
    }
}

object CategoryDrafts {
    private val slugPattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

    fun add(
        nameEn: String,
        nameAr: String,
        slug: String,
        parentId: String?,
        custom: List<Category>,
    ): UiResult<NewCategory> {
        val id = slug.trim()
        if (!slugPattern.matches(id)) {
            return UiResult.Rejected("Use a lowercase id, such as pets or pet-care.")
        }
        val english = nameEn.trim()
        val arabic = nameAr.trim()
        if (english.isEmpty() || arabic.isEmpty()) {
            return UiResult.Rejected("Enter both names.")
        }
        val parent = parentId?.trim()?.takeIf { it.isNotEmpty() }
        val tree = CategoryOptions.tree(custom)
        if (parent != null && CategoryOptions.find(tree, parent) == null) {
            return UiResult.Rejected("Choose a parent from the current tree.")
        }
        val sortOrder = (tree.maxOfOrNull { it.sortOrder } ?: 0) + 1
        return UiResult.Ready(
            NewCategory(
                id = id,
                parentId = parent,
                nameEn = english,
                nameAr = arabic,
                slug = id,
                sortOrder = sortOrder,
            ),
        )
    }

    fun update(
        category: Category,
        nameEn: String,
        nameAr: String,
        parentId: String?,
        custom: List<Category>,
    ): UiResult<CategoryChange> {
        if (!CategoryOptions.canEdit(category)) {
            return UiResult.Rejected("Built-in categories stay on the seeded tree.")
        }
        val english = nameEn.trim()
        val arabic = nameAr.trim()
        if (english.isEmpty() || arabic.isEmpty()) {
            return UiResult.Rejected("Enter both names.")
        }
        val parent = parentId?.trim()?.takeIf { it.isNotEmpty() }
        val tree = CategoryOptions.tree(custom)
        if (parent != null && CategoryOptions.find(tree, parent) == null) {
            return UiResult.Rejected("Choose a parent from the current tree.")
        }
        return UiResult.Ready(
            CategoryChange(
                parentId = parent,
                nameEn = english,
                nameAr = arabic,
                sortOrder = category.sortOrder,
            ),
        )
    }
}
