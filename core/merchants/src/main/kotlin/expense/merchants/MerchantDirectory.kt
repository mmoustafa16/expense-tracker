package expense.merchants

enum class AliasType {
    EXACT_RAW,
    NORMALIZED_KEY,
}

enum class AliasSource {
    SYSTEM,
    USER,
}

data class Merchant(
    val id: String,
    val displayName: String,
    val normalizedKey: String,
)

data class MerchantAlias(
    val id: String,
    val merchantId: String,
    val patternType: AliasType,
    val pattern: String,
    val priority: Int,
    val source: AliasSource,
)

object MerchantDirectory {
    data class Resolved(
        val merchants: List<Merchant>,
        val merchant: Merchant?,
    )

    fun resolve(
        raw: String?,
        merchants: List<Merchant>,
        aliases: List<MerchantAlias>,
        newId: () -> String,
    ): Resolved {
        if (raw.isNullOrBlank()) {
            return Resolved(merchants, null)
        }
        val trimmed = raw.trim()
        val exact = aliases
            .filter { it.patternType == AliasType.EXACT_RAW && it.pattern == trimmed }
            .maxByOrNull { it.priority }
        if (exact != null) {
            val merchant = merchants.find { it.id == exact.merchantId }
            if (merchant != null) {
                return Resolved(merchants, merchant)
            }
        }
        val key = MerchantKey.normalize(trimmed)
        val byKey = aliases
            .filter { it.patternType == AliasType.NORMALIZED_KEY && it.pattern == key && key.isNotBlank() }
            .maxByOrNull { it.priority }
        if (byKey != null) {
            val merchant = merchants.find { it.id == byKey.merchantId }
            if (merchant != null) {
                return Resolved(merchants, merchant)
            }
        }
        val existing = merchants.find { key.isNotBlank() && it.normalizedKey == key }
        if (existing != null) {
            return Resolved(merchants, existing)
        }
        val created = Merchant(
            id = newId(),
            displayName = trimmed,
            normalizedKey = key,
        )
        return Resolved(merchants + created, created)
    }
}
