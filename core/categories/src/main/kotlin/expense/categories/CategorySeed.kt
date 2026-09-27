package expense.categories

object CategorySeed {
    val all: List<Category> = listOf(
        category("food", null, "Food", "طعام", 10),
        category("groceries", "food", "Groceries", "بقالة", 11),
        category("restaurants", "food", "Restaurants", "مطاعم", 12),
        category("transport", null, "Transport", "مواصلات", 20),
        category("fuel", "transport", "Fuel", "وقود", 21),
        category("telecom", null, "Telecom", "اتصالات", 30),
        category("utilities", null, "Utilities", "مرافق", 40),
        category("health", null, "Health", "صحة", 50),
        category("shopping", null, "Shopping", "تسوق", 60),
        category("cash", null, "Cash", "نقد", 70),
        category("fees", null, "Fees", "رسوم", 80),
        category("transfers", null, "Transfers", "تحويلات", 90),
        category("other", null, "Other", "أخرى", 100),
    )

    fun bySlug(slug: String): Category? = all.find { it.slug == slug }

    fun rollup(slug: String): List<Category> {
        val start = bySlug(slug) ?: return emptyList()
        val chain = mutableListOf(start)
        var parentId = start.parentId
        while (parentId != null) {
            val parent = bySlug(parentId) ?: break
            chain += parent
            parentId = parent.parentId
        }
        return chain
    }

    private fun category(
        slug: String,
        parentId: String?,
        nameEn: String,
        nameAr: String,
        sortOrder: Int,
    ): Category {
        return Category(
            id = slug,
            parentId = parentId,
            nameEn = nameEn,
            nameAr = nameAr,
            slug = slug,
            sortOrder = sortOrder,
        )
    }
}
