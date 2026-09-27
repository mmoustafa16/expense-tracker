package expense.categories

enum class CategorySource {
    RULE,
    USER,
    UNCATEGORIZED,
}

enum class RuleSource {
    SYSTEM,
    USER,
}

enum class MatchType {
    MERCHANT,
    NORMALIZED_CONTAINS,
    KEYWORD,
}

data class CategoryRule(
    val id: String,
    val matchType: MatchType,
    val pattern: String,
    val categoryId: String,
    val priority: Int,
    val source: RuleSource,
)

data class CategoryAssignment(
    val categoryId: String,
    val source: CategorySource,
    val merchantSpecific: Boolean,
)

object CategoryResolver {
    fun resolve(
        merchantId: String?,
        normalizedKey: String?,
        rules: List<CategoryRule>,
    ): CategoryAssignment? {
        val merchantRule = rules
            .asSequence()
            .filter { it.matchType == MatchType.MERCHANT && merchantId != null && it.pattern == merchantId }
            .filter { CategorySeed.bySlug(it.categoryId) != null }
            .maxWithOrNull(ruleOrder)
        if (merchantRule != null) {
            return assignment(merchantRule, merchantSpecific = true)
        }
        val key = normalizedKey?.takeIf { it.isNotBlank() } ?: return null
        val textual = rules
            .asSequence()
            .filter { matchesText(it, key) && CategorySeed.bySlug(it.categoryId) != null }
            .maxWithOrNull(ruleOrder)
            ?: return null
        return assignment(textual, merchantSpecific = false)
    }

    private fun matchesText(rule: CategoryRule, key: String): Boolean {
        return when (rule.matchType) {
            MatchType.KEYWORD -> key.split(' ').any { it == rule.pattern }
            MatchType.NORMALIZED_CONTAINS -> rule.pattern.isNotBlank() && key.contains(rule.pattern)
            MatchType.MERCHANT -> false
        }
    }

    private fun assignment(rule: CategoryRule, merchantSpecific: Boolean): CategoryAssignment {
        val source = if (rule.source == RuleSource.USER) CategorySource.USER else CategorySource.RULE
        return CategoryAssignment(rule.categoryId, source, merchantSpecific)
    }

    private val ruleOrder = compareBy<CategoryRule> { it.priority }.thenBy { if (it.source == RuleSource.USER) 1 else 0 }
}
