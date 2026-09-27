package expense.ingest

import expense.categories.CategoryRule
import expense.categories.CategorySeed
import expense.categories.CategorySource
import expense.categories.MatchType
import expense.categories.RuleSource
import expense.ledger.Correction
import expense.ledger.CorrectionField
import expense.ledger.LedgerState
import expense.ledger.SpendPolicy
import expense.ledger.Transaction
import expense.ledger.TransactionStatus
import expense.merchants.AliasSource
import expense.merchants.AliasType
import expense.merchants.MerchantAlias
import expense.merchants.MerchantDirectory
import expense.merchants.MerchantKey
import expense.parse.TransactionKind

/**
 * Reapplies user corrections onto projected rows.
 * Bank profiles are not an input and are not an output.
 */
internal object CorrectionOverlay {
    fun apply(state: LedgerState, ids: IdGenerator): LedgerState {
        var merchants = state.merchants
        val aliases = state.aliases.toMutableList()
        val rules = state.categoryRules.toMutableList()
        var transactions = state.transactions

        for (correction in state.corrections.sortedBy { it.createdAt }) {
            val index = transactions.indexOfFirst { it.dedupKey == correction.dedupKey }
            if (index < 0) continue
            var tx = transactions[index]
            when (correction.field) {
                CorrectionField.MERCHANT -> {
                    val name = correction.updatedValue?.trim().orEmpty()
                    if (name.isEmpty()) continue
                    val resolved = MerchantDirectory.resolve(name, merchants, aliases, ids::newId)
                    merchants = resolved.merchants
                    val merchant = resolved.merchant ?: continue
                    if (correction.applyForward) {
                        rememberMerchant(tx, merchant.id, aliases, ids)
                    }
                    tx = tx.copy(merchantId = merchant.id)
                }
                CorrectionField.CATEGORY -> {
                    val slug = correction.updatedValue?.trim().orEmpty()
                    if (CategorySeed.bySlug(slug) == null) continue
                    tx = tx.copy(categoryId = slug, categorySource = CategorySource.USER)
                    val merchantId = tx.merchantId
                    if (correction.applyForward && merchantId != null) {
                        rememberCategory(merchantId, slug, rules, ids)
                    }
                }
                CorrectionField.KIND -> {
                    val kind = correction.updatedValue?.let { value ->
                        runCatching { TransactionKind.valueOf(value) }.getOrNull()
                    } ?: continue
                    tx = tx.copy(
                        kind = kind,
                        includeInSpend = SpendPolicy.include(kind, tx.status),
                    )
                }
                CorrectionField.AMOUNT -> {
                    val minor = correction.updatedValue?.toLongOrNull() ?: continue
                    if (minor < 0) continue
                    tx = tx.copy(amount = tx.amount.copy(amountMinor = minor))
                }
                CorrectionField.ACCOUNT -> {
                    tx = tx.copy(accountId = correction.updatedValue)
                }
                CorrectionField.INCLUDE_IN_SPEND -> {
                    val include = correction.updatedValue?.toBooleanStrictOrNull() ?: continue
                    val status = when {
                        tx.status == TransactionStatus.VOIDED -> tx.status
                        include -> TransactionStatus.POSTED
                        else -> TransactionStatus.EXCLUDED
                    }
                    val included = include && status != TransactionStatus.VOIDED
                    tx = tx.copy(includeInSpend = included, status = status)
                }
            }
            transactions = transactions.toMutableList().also { it[index] = tx }
        }
        return state.copy(
            merchants = merchants,
            aliases = aliases,
            categoryRules = rules,
            transactions = transactions,
        )
    }

    private fun rememberMerchant(
        tx: Transaction,
        merchantId: String,
        aliases: MutableList<MerchantAlias>,
        ids: IdGenerator,
    ) {
        val raw = tx.merchantRaw?.trim().orEmpty()
        if (raw.isEmpty()) return
        addAlias(
            aliases,
            MerchantAlias(
                id = ids.newId(),
                merchantId = merchantId,
                patternType = AliasType.EXACT_RAW,
                pattern = raw,
                priority = 200,
                source = AliasSource.USER,
            ),
        )
        val key = MerchantKey.normalize(raw)
        if (key.isBlank()) return
        addAlias(
            aliases,
            MerchantAlias(
                id = ids.newId(),
                merchantId = merchantId,
                patternType = AliasType.NORMALIZED_KEY,
                pattern = key,
                priority = 100,
                source = AliasSource.USER,
            ),
        )
    }

    private fun addAlias(aliases: MutableList<MerchantAlias>, alias: MerchantAlias) {
        val exists = aliases.any {
            it.merchantId == alias.merchantId &&
                it.patternType == alias.patternType &&
                it.pattern == alias.pattern
        }
        if (!exists) aliases += alias
    }

    private fun rememberCategory(
        merchantId: String,
        slug: String,
        rules: MutableList<CategoryRule>,
        ids: IdGenerator,
    ) {
        val exists = rules.any {
            it.matchType == MatchType.MERCHANT &&
                it.pattern == merchantId &&
                it.categoryId == slug &&
                it.source == RuleSource.USER
        }
        if (exists) return
        rules += CategoryRule(
            id = ids.newId(),
            matchType = MatchType.MERCHANT,
            pattern = merchantId,
            categoryId = slug,
            priority = 100,
            source = RuleSource.USER,
        )
    }
}
