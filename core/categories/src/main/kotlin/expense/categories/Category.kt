package expense.categories

data class Category(
    val id: String,
    val parentId: String?,
    val nameEn: String,
    val nameAr: String,
    val slug: String,
    val sortOrder: Int,
    val system: Boolean = true,
)
