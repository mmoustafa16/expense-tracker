package expense.intelligence

import expense.money.DigitFold
import expense.money.MoneyText
import expense.parse.AmountResolution
import expense.parse.AmountRole
import expense.parse.RoledAmount
import java.time.DateTimeException
import java.time.LocalDateTime

/**
 * Copies entities out of one message using the language of money rather than
 * the habits of any institution.
 *
 * Amounts are located, then named by [AmountRoleTagger], then reduced to a
 * single event value. A message that states a transaction amount and a
 * remaining balance resolves cleanly, because the balance never competes for
 * the event role. Only two different values claiming the same event role are an
 * ambiguity.
 */
class DeterministicEntityExtractor(
    private val roleTagger: AmountRoleTagger = LexicalAmountRoleTagger(),
) : FinancialEntityExtractor {
    override fun extract(message: SmsText): ExtractedEntities {
        val folded = DigitFold.fold(message.body)
        val roled = resolveRoles(folded)
        val claimed = roled.map { it.start..it.end }
        return ExtractedEntities(
            amounts = roled,
            resolution = resolutionOf(roled),
            merchant = findMerchant(folded),
            instrument = PaymentInstruments.find(folded, claimed),
            reference = findReference(folded),
            occurredAt = findOccurred(folded),
        )
    }

    /**
     * Names every value, then promotes at most one unnamed value to the event
     * role when nothing else claims it. Promotion is what keeps terse messages
     * working; it never overrides a value the message itself explained.
     */
    private fun resolveRoles(folded: String): List<RoledAmount> {
        val tagged = roleTagger.tag(folded, AmountScanner.scan(folded))
        if (tagged.any { it.role.isEventValue() }) return tagged
        val promotable = tagged.firstOrNull { it.role == AmountRole.UNKNOWN } ?: return tagged
        return tagged.map { item ->
            if (item.start == promotable.start) item.copy(role = AmountRole.TRANSACTION_AMOUNT) else item
        }
    }

    private fun resolutionOf(amounts: List<RoledAmount>): AmountResolution {
        val eventValues = amounts.filter { it.role.isEventValue() }
        return when {
            eventValues.isEmpty() -> AmountResolution.MISSING
            eventValues.map { it.amount }.distinct().size > 1 -> AmountResolution.AMBIGUOUS
            else -> AmountResolution.RESOLVED
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

    /**
     * Day and month order is not stated in an SMS. When the first field cannot
     * be a day the fields are swapped; when both readings are possible the
     * common day-first order is used.
     */
    private fun civil(day: Int, month: Int, year: Int, time: String): LocalDateTime? {
        val dayFirst = day in 1..31 && month in 1..12
        val monthFirst = month in 1..31 && day in 1..12
        val resolvedDay = if (dayFirst) day else if (monthFirst) month else return null
        val resolvedMonth = if (dayFirst) month else if (monthFirst) day else return null
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
            LocalDateTime.of(year, resolvedMonth, resolvedDay, hour, minute)
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
        val merchantPattern = Regex(
            """(?i)(?:\b(?:at|from|by|to)\b|عند|لدى|من)\s+([A-Za-z\u0600-\u06FF][A-Za-z\u0600-\u06FF0-9&'.-]*(?:\s+[A-Za-z\u0600-\u06FF][A-Za-z\u0600-\u06FF0-9&'.-]*)?)""",
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

/**
 * Checks that every claimed entity is traceable to the message.
 *
 * This answers extraction validity and nothing else. Whether an event happened
 * is [EventStateDetector]'s question and whether it may post is
 * [FinancialRouting]'s, so a message with no amount is not invalid here.
 */
class DeterministicTransactionValidator : TransactionValidator {
    override fun validate(message: SmsText, entities: ExtractedEntities): ValidationResult {
        val folded = DigitFold.fold(message.body)
        val reasons = mutableListOf<String>()
        entities.amounts.forEach { roled ->
            if (!folded.contains(roled.token)) {
                reasons += "amount_not_in_message"
            } else if (MoneyText.parse(roled.token, roled.amount.currency) != roled.amount) {
                reasons += "amount_contradicts_token"
            }
            if (roled.currencyToken.isNotBlank() && !folded.contains(roled.currencyToken, ignoreCase = true)) {
                reasons += "currency_not_in_message"
            }
            if (roled.amount.currency.code !in supported) reasons += "currency_unsupported"
        }
        if (!entities.merchant.isNullOrBlank() && !folded.contains(entities.merchant, ignoreCase = true)) {
            reasons += "merchant_not_in_message"
        }
        val mask = entities.accountMask
        if (!mask.isNullOrBlank() && !folded.contains(mask)) reasons += "mask_not_in_message"
        if (!entities.reference.isNullOrBlank() && !folded.contains(entities.reference, ignoreCase = true)) {
            reasons += "reference_not_in_message"
        }
        return ValidationResult(accepted = reasons.isEmpty(), contradictions = reasons.distinct())
    }

    private companion object {
        val supported = setOf("EGP", "USD", "EUR", "GBP", "SAR", "AED")
    }
}
