package expense.categories

data class NewCategory(
    val id: String,
    val parentId: String?,
    val nameEn: String,
    val nameAr: String,
    val slug: String,
    val sortOrder: Int,
)

data class CategoryChange(
    val parentId: String?,
    val nameEn: String,
    val nameAr: String,
    val sortOrder: Int,
)

/**
 * Custom categories sit beside [CategorySeed]. Creating or editing one does not
 * take a bank profile or a parser.
 */
object CategoryCatalog {
    fun acceptedIds(customCategoryIds: Set<String>): Set<String> {
        return CategorySeed.all.map { it.id }.toSet() + customCategoryIds
    }

    fun canonicalId(value: String, custom: List<Category>): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        CategorySeed.bySlug(trimmed)?.id?.let { return it }
        return custom.firstOrNull { it.id == trimmed || it.slug == trimmed }?.id
    }

    fun add(custom: List<Category>, draft: NewCategory): List<Category> {
        val id = draft.id.trim()
        val slug = draft.slug.trim()
        val nameEn = draft.nameEn.trim()
        val nameAr = draft.nameAr.trim()
        require(id.isNotBlank()) { "category id is required" }
        require(slug.isNotBlank()) { "category slug is required" }
        require(nameEn.isNotBlank() && nameAr.isNotBlank()) { "category names are required" }
        require(canonicalId(id, custom) == null) { "category id already exists" }
        require(custom.none { it.slug == slug } && CategorySeed.bySlug(slug) == null && CategorySeed.all.none { it.id == slug }) {
            "category slug already exists"
        }
        val parentId = draft.parentId?.trim()?.takeIf { it.isNotEmpty() }
        if (parentId != null) {
            require(canonicalId(parentId, custom) != null) { "parent category does not exist" }
        }
        return custom + Category(
            id = id,
            parentId = parentId,
            nameEn = nameEn,
            nameAr = nameAr,
            slug = slug,
            sortOrder = draft.sortOrder,
            system = false,
        )
    }

    fun update(custom: List<Category>, id: String, change: CategoryChange): List<Category> {
        val index = custom.indexOfFirst { it.id == id }
        require(index >= 0) { "only a custom category can be changed" }
        require(CategorySeed.bySlug(id) == null) { "seed categories stay on the default tree" }
        val nameEn = change.nameEn.trim()
        val nameAr = change.nameAr.trim()
        require(nameEn.isNotBlank() && nameAr.isNotBlank()) { "category names are required" }
        val parentId = change.parentId?.trim()?.takeIf { it.isNotEmpty() }
        if (parentId != null) {
            require(canonicalId(parentId, custom) != null) { "parent category does not exist" }
            require(!createsCycle(custom, id, parentId)) { "category parent would cycle" }
        }
        val current = custom[index]
        val updated = current.copy(
            parentId = parentId,
            nameEn = nameEn,
            nameAr = nameAr,
            sortOrder = change.sortOrder,
            system = false,
        )
        return custom.toMutableList().also { it[index] = updated }
    }

    fun descendants(roots: Set<String>, tree: List<Category>): Set<String> {
        if (roots.isEmpty()) return emptySet()
        val children = tree.groupBy { it.parentId }
        val pending = ArrayDeque(roots)
        val seen = linkedSetOf<String>()
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!seen.add(id)) continue
            children[id].orEmpty().forEach { pending.add(it.id) }
        }
        return seen
    }

    private fun createsCycle(custom: List<Category>, id: String, parentId: String): Boolean {
        val parents = (CategorySeed.all + custom).associate { it.id to it.parentId }
        var cursor: String? = parentId
        val seen = mutableSetOf<String>()
        while (cursor != null) {
            if (cursor == id || !seen.add(cursor)) return true
            cursor = parents[cursor]
        }
        return false
    }
}
