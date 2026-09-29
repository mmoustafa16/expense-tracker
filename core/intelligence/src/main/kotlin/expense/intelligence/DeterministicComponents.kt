package expense.intelligence

import expense.money.Currency
import expense.money.DigitFold
import expense.money.Money
import expense.money.MoneyText
import java.time.DateTimeException
import java.time.LocalDateTime

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
            accountMask = paymentInstrument(folded)?.mask,
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
        val end: Int,
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
                found += FoundAmount(
                    money, numberToken, currency, currencyToken,
                    balance = false, start = start, end = hit.range.last + 1,
                )
            }
        }
        val ordered = found.distinctBy { it.start }.sortedBy { it.start }
        return ordered.mapIndexed { index, item ->
            val previous = if (index == 0) 0 else ordered[index - 1].end
            val between = folded.substring(previous, item.start)
            item.copy(balance = balanceCue.containsMatchIn(between))
        }
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

    private fun findReference(folded: String): String? {
        return referencePattern.find(folded)?.groupValues?.get(1)
    }

    private fun findOccurred(folded: String): LocalDateTime? {
        numericDate.find(folded)?.let { match ->
            return civil(
                day = match.groupValues[1].toInt(),
                month = match.groupValues[2].toInt(),
                year = expandYear(match.groupValues[3].toInt()),
                time = match.groupValues[4],
            )
        }
        namedDate.find(folded)?.let { match ->
            val month = months[match.groupValues[2].lowercase().take(3)] ?: return null
            return civil(
                day = match.groupValues[1].toInt(),
                month = month,
                year = match.groupValues[3].toInt(),
                time = match.groupValues[4],
            )
        }
        return null
    }

    private fun civil(day: Int, month: Int, year: Int, time: String): LocalDateTime? {
        val hour: Int
        val minute: Int
        if (time.isBlank()) {
            hour = 0
            minute = 0
        } else {
            val parts = time.split(':')
            hour = parts[0].toInt()
            minute = parts[1].toInt()
        }
        return try {
            LocalDateTime.of(year, month, day, hour, minute)
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun expandYear(year: Int): Int {
        return when {
            year >= 100 -> year
            year >= 70 -> 1900 + year
            else -> 2000 + year
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
            """(?i)(?:\b(?:at|from|by|to)\b|عند|لدى)\s+([A-Za-z\u0600-\u06FF][A-Za-z\u0600-\u06FF0-9&'.-]*(?:\s+[A-Za-z\u0600-\u06FF][A-Za-z\u0600-\u06FF0-9&'.-]*)?)""",
        )
        val merchantStops = setOf(
            "on", "ref", "reference", "available", "balance", "for", "with", "card", "ending",
            "your", "the", "a", "an", "another", "this", "that", "was", "is", "has",
        )
        val referencePattern = Regex("""(?i)\bref(?:erence)?[:\s#-]+([A-Za-z0-9]{2,})""")
        val numericDate = Regex(
            """\b(\d{1,2})[/\-](\d{1,2})[/\-](\d{2}|\d{4})(?:\s+at)?(?:\s+(\d{1,2}:\d{2}))?\b""",
            RegexOption.IGNORE_CASE,
        )
        val namedDate = Regex(
            """(?i)\b(\d{1,2})[\s\-]((?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*)[\s\-](\d{4})(?:\s+at)?(?:\s+(\d{1,2}:\d{2}))?\b""",
        )
        val months = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
        )
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

/**
 * Last-4 digits the SMS states next to a card, an account, or a mask.
 * A bare number, an order number, and a phone number are not an instrument.
 */
data class PaymentInstrument(
    val mask: String,
    val kind: InstrumentKind,
)

enum class InstrumentKind {
    CREDIT_CARD,
    DEBIT_CARD,
    ACCOUNT,
    CARD,
    UNSPECIFIED,
}

fun paymentInstrument(body: String): PaymentInstrument? {
    val folded = DigitFold.fold(body)
    val match = instrumentPattern.find(folded) ?: return null
    val mask = match.groupValues.drop(1).firstOrNull { it.length == 4 && it.all(Char::isDigit) } ?: return null
    val start = (match.range.first - 48).coerceAtLeast(0)
    val window = folded.substring(start, match.range.last + 1)
    return PaymentInstrument(mask, instrumentKind(window))
}

private fun instrumentKind(window: String): InstrumentKind {
    return when {
        creditInstrument.containsMatchIn(window) -> InstrumentKind.CREDIT_CARD
        debitInstrument.containsMatchIn(window) -> InstrumentKind.DEBIT_CARD
        accountInstrument.containsMatchIn(window) -> InstrumentKind.ACCOUNT
        cardInstrument.containsMatchIn(window) -> InstrumentKind.CARD
        else -> InstrumentKind.UNSPECIFIED
    }
}

private val instrumentWord =
    """(?:\b(?:credit\s+card|debit\s+card|card|account|acct)\b|\ba/c\b|بطاقة\s+ائتمان|بطاقة\s+خصم|بطاقتك|بطاقة|حسابك|حساب|المنتهية)"""
private val instrumentPattern = Regex(
    """(?i)(?:$instrumentWord\s*(?:(?:no\.?|number|رقم|ending|ends|#)\s*)?(?:(?:with|in|بـ|برقم)\s*)?(?:[*xX•●·]|\s)*(\d{4})\b)|(?:[*xX•●·]{2,}\s*(\d{4})\b)|(?:\b(?:ending|ends)\s+(?:(?:with|in)\s+)?(?:[*xX•●·]|\s)*(\d{4})\b)""",
)
private val creditInstrument = Regex("""(?i)\bcredit\s+card\b|بطاقة\s+ائتمان|ائتمان""")
private val debitInstrument = Regex("""(?i)\bdebit\s+card\b|بطاقة\s+خصم""")
private val accountInstrument = Regex("""(?i)\b(?:account|acct|a/c)\b|حساب""")
private val cardInstrument = Regex("""(?i)\bcard\b|بطاقة""")
