package expense.intelligence

import expense.money.Currency
import expense.money.DigitFold
import expense.money.Money
import expense.money.MoneyText
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class DeterministicEntityExtractor : FinancialEntityExtractor {
    override fun extract(message: SmsText): ExtractedEntities {
        val folded = DigitFold.fold(message.body)
        val amounts = findAmounts(folded)
        val transactionAmounts = amounts.filter { !it.balance }
        val balanceAmounts = amounts.filter { it.balance }
        val amountChoice = when {
            transactionAmounts.map { it.money }.distinct().size > 1 -> null
            transactionAmounts.size == 1 -> transactionAmounts.single()
            transactionAmounts.size > 1 -> transactionAmounts.first()
            else -> null
        }
        val role = when {
            transactionAmounts.map { it.money }.distinct().size > 1 -> AmountRole.AMBIGUOUS
            amountChoice != null -> AmountRole.TRANSACTION
            balanceAmounts.isNotEmpty() -> AmountRole.BALANCE
            else -> AmountRole.ABSENT
        }
        val balance = balanceAmounts.firstOrNull()?.money
        val occurred = findOccurred(folded)
        return ExtractedEntities(
            amount = amountChoice?.money,
            amountToken = amountChoice?.token,
            currency = amountChoice?.currency,
            currencyToken = amountChoice?.currencyToken,
            merchant = findMerchant(folded),
            accountMask = findMask(folded),
            reference = findReference(folded),
            occurredAt = occurred,
            balance = balance,
            amountRole = role,
        )
    }

    private data class AmountPattern(
        val regex: Regex,
        val currencyGroup: Int,
        val numberGroup: Int,
    )

    private data class FoundAmount(
        val money: Money,
        val token: String,
        val currency: Currency,
        val currencyToken: String,
        val balance: Boolean,
        val start: Int,
    )

    private fun findAmounts(folded: String): List<FoundAmount> {
        val found = mutableListOf<FoundAmount>()
        for (spec in amountPatterns) {
            spec.regex.findAll(folded).forEach { hit ->
                val currencyToken = hit.groupValues[spec.currencyGroup]
                val numberToken = hit.groupValues[spec.numberGroup]
                val currency = currencyOf(currencyToken) ?: return@forEach
                val money = MoneyText.parse(numberToken, currency) ?: return@forEach
                val start = hit.range.first
                val windowStart = (start - 40).coerceAtLeast(0)
                val prefix = folded.substring(windowStart, start)
                found += FoundAmount(money, numberToken, currency, currencyToken, balanceCue.containsMatchIn(prefix), start)
            }
        }
        return found.distinctBy { it.start }
    }

    private fun currencyOf(token: String): Currency? {
        return when (token.uppercase()) {
            "EGP", "LE" -> Currency.EGP
            "USD" -> Currency.USD
            "EUR" -> Currency.EUR
            "GBP" -> Currency.GBP
            "جنيه" -> Currency.EGP
            "دولار" -> Currency.USD
            "يورو" -> Currency.EUR
            else -> null
        }
    }

    private fun findMerchant(folded: String): String? {
        val match = merchantPattern.find(folded) ?: return null
        val words = match.groupValues[1].split(Regex("""\s+"""))
        val kept = words.takeWhile { it.lowercase() !in merchantStops }
        val name = kept.joinToString(" ").trim { it.isWhitespace() || it == '.' || it == ',' }
        return name.ifBlank { null }
    }

    private fun findMask(folded: String): String? {
        val match = maskPattern.find(folded) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
    }

    private fun findReference(folded: String): String? {
        return referencePattern.find(folded)?.groupValues?.get(1)
    }

    private fun findOccurred(folded: String): LocalDateTime? {
        val match = occurredPattern.find(folded) ?: return null
        val time = match.groupValues[2]
        if (time.isBlank()) return null
        return try {
            LocalDateTime.parse("${match.groupValues[1]} $time", civilTime)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private companion object {
        val balanceCue = Regex("""(?i)(balance|available\s+limit|الرصيد)""")
        val amountPatterns = listOf(
            AmountPattern(Regex("""(?i)\b(EGP|USD|EUR|GBP|LE)\s+([0-9]+(?:[.,][0-9]+)*)"""), currencyGroup = 1, numberGroup = 2),
            AmountPattern(Regex("""(?i)\b([0-9]+(?:[.,][0-9]+)*)\s+(EGP|USD|EUR|GBP|LE)\b"""), currencyGroup = 2, numberGroup = 1),
            AmountPattern(Regex("""(جنيه|دولار|يورو)\s*([0-9]+(?:[.,][0-9]+)*)"""), currencyGroup = 1, numberGroup = 2),
            AmountPattern(Regex("""([0-9]+(?:[.,][0-9]+)*)\s*(جنيه|دولار|يورو)"""), currencyGroup = 2, numberGroup = 1),
        )
        val merchantPattern = Regex(
            """(?i)(?:\b(?:at|from|by)\b|عند|لدى)\s+([A-Za-z][A-Za-z0-9&'.-]*(?:\s+[A-Za-z][A-Za-z0-9&'.-]*)?)""",
        )
        val merchantStops = setOf("on", "ref", "reference", "available", "balance", "for", "with", "card", "ending")
        val maskPattern = Regex(
            """(?i)\b(?:card|account|acct)\s+ending(?:\s+with)?\s+\**(\d{4})\b|\bending(?:\s+with)?\s+\**(\d{4})\b|\b(?:card|account|acct)\s*#+\s*(\d{4})\b""",
        )
        val referencePattern = Regex("""(?i)\bref(?:erence)?[:\s#-]+([A-Za-z0-9]{2,})""")
        val occurredPattern = Regex("""\b(\d{2}/\d{2}/\d{4})(?:\s+(\d{2}:\d{2}))?\b""")
        val civilTime: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")
    }
}

class DeterministicTransactionValidator : TransactionValidator {
    override fun validate(
        message: SmsText,
        classification: Classification,
        entities: ExtractedEntities,
    ): ValidationResult {
        val folded = DigitFold.fold(message.body)
        val reasons = mutableListOf<String>()
        if (entities.amount != null) {
            val token = entities.amountToken
            if (token.isNullOrBlank() || !folded.contains(token)) {
                reasons += "amount_not_in_message"
            } else if (entities.currency != null && MoneyText.parse(token, entities.currency) != entities.amount) {
                reasons += "amount_contradicts_token"
            }
        }
        if (entities.currency != null && entities.currency.code !in supported) reasons += "currency_unsupported"
        if (!entities.currencyToken.isNullOrBlank() && !folded.contains(entities.currencyToken, ignoreCase = true)) {
            reasons += "currency_not_in_message"
        }
        if (!entities.merchant.isNullOrBlank() && !folded.contains(entities.merchant, ignoreCase = true)) {
            reasons += "merchant_not_in_message"
        }
        if (!entities.accountMask.isNullOrBlank() && !folded.contains(entities.accountMask)) reasons += "mask_not_in_message"
        if (!entities.reference.isNullOrBlank() && !folded.contains(entities.reference, ignoreCase = true)) {
            reasons += "reference_not_in_message"
        }
        val forbidden = !classification.type.isLedgerCandidate()
        if (!forbidden) {
            if (entities.amount == null || entities.amountRole != AmountRole.TRANSACTION) reasons += "amount_missing"
            if (classification.ambiguous) reasons += "ambiguous_class"
        }
        val review = !forbidden && reasons.isNotEmpty()
        return ValidationResult(
            accepted = reasons.isEmpty(),
            contradictions = reasons,
            forcesReview = review,
            ledgerForbidden = forbidden,
        )
    }

    private companion object {
        val supported = setOf("EGP", "USD", "EUR", "GBP")
    }
}
