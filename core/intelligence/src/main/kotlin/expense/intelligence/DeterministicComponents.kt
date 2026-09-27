package expense.intelligence

import expense.money.Currency
import expense.money.DigitFold
import expense.money.Money
import expense.money.MoneyText
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class DeterministicBankIdentifier(
    private val senders: List<RegisteredSender>,
) : BankIdentifier {
    override fun identify(message: SmsText): BankIdentification {
        val trimmed = message.sender.trim()
        if (trimmed.isEmpty()) return BankIdentification(emptyList(), unknown = true)
        val hits = senders.filter { sender -> sender.senderIds.any { it.trim() == trimmed } }
        if (hits.isEmpty()) return BankIdentification(emptyList(), unknown = true)
        val confidence = if (hits.size == 1) 90 else 40
        return BankIdentification(
            candidates = hits.map { BankCandidate(it.institutionId, it.displayName, confidence) },
            unknown = false,
        )
    }
}

class DeterministicTransactionClassifier : TransactionClassifier {
    override fun classify(message: SmsText): Classification {
        val text = DigitFold.fold(message.body)
        if (otp.containsMatchIn(text)) return Classification(TransactionClass.OTP, 95, ambiguous = false)
        val transactions = transactionCues.filter { it.pattern.containsMatchIn(text) }
        val specific = preferSpecific(transactions)
        if (specific.isEmpty()) return nonTransaction(text)
        val classes = specific.map { it.type }.toSet()
        if (classes.size > 1) {
            val top = specific.maxBy { it.weight }
            return Classification(top.type, confidence = 45, ambiguous = true)
        }
        val chosen = specific.maxBy { it.weight }
        return Classification(chosen.type, confidence = chosen.weight, ambiguous = false)
    }

    private fun nonTransaction(text: String): Classification {
        val type = when {
            promotion.containsMatchIn(text) -> TransactionClass.PROMOTION
            statement.containsMatchIn(text) -> TransactionClass.STATEMENT
            paymentDue.containsMatchIn(text) -> TransactionClass.PAYMENT_DUE
            balance.containsMatchIn(text) -> TransactionClass.BALANCE_NOTIFICATION
            else -> TransactionClass.OTHER_NON_TRANSACTION
        }
        val confidence = if (type == TransactionClass.OTHER_NON_TRANSACTION) 30 else 90
        return Classification(type, confidence, ambiguous = false)
    }

    private fun preferSpecific(cues: List<Cue>): List<Cue> {
        val types = cues.map { it.type }.toSet()
        return cues.filter { cue ->
            when (cue.type) {
                TransactionClass.CARD_PURCHASE ->
                    TransactionClass.FEE !in types &&
                        TransactionClass.REFUND !in types &&
                        TransactionClass.REVERSAL !in types &&
                        TransactionClass.CASH_WITHDRAWAL !in types &&
                        TransactionClass.TRANSFER !in types
                TransactionClass.PAYMENT ->
                    TransactionClass.CARD_PURCHASE !in types &&
                        TransactionClass.TRANSFER !in types &&
                        TransactionClass.FEE !in types
                else -> true
            }
        }
    }

    private data class Cue(
        val type: TransactionClass,
        val pattern: Regex,
        val weight: Int,
    )

    private companion object {
        val otp = Regex(
            """(?i)(\botp\b|one[\s-]*time\s+(password|passcode|code|pin)|verification\s+code|رمز التحقق|كود التحقق)""",
        )
        val promotion = Regex("""(?i)(\bsave\b|\bdiscount\b|\boffer\b|\bpromo\b|use\s+code|خصم\s*\d+\s*%)""")
        val statement = Regex("""(?i)\bstatement\b|كشف حساب""")
        val paymentDue = Regex("""(?i)\bpayment\s+due\b|\bpayment\s+reminder\b|مستحق""")
        val balance = Regex("""(?i)\b(available\s+balance|balance\s+is|account\s+balance)\b|الرصيد""")
        val transactionCues = listOf(
            Cue(TransactionClass.REVERSAL, Regex("""(?i)\b(reversal|reversed)\b|تم عكس"""), 90),
            Cue(TransactionClass.REFUND, Regex("""(?i)\b(refund|refunded)\b|تم رد"""), 90),
            Cue(TransactionClass.FEE, Regex("""(?i)\bfees?\b|رسوم"""), 88),
            Cue(TransactionClass.CASH_WITHDRAWAL, Regex("""(?i)\b(cash\s+withdrawal|withdrew|withdrawn|atm\s+withdrawal)\b|سحب نقدي"""), 90),
            Cue(TransactionClass.TRANSFER, Regex("""(?i)\b(transferred|transfer\s+of|transfer\s+to|transfer\s+from)\b|تم تحويل"""), 88),
            Cue(TransactionClass.CARD_PURCHASE, Regex("""(?i)\b(charged|debited|purchased|purchase\s+of|purchase\s+at|card\s+purchase|spent)\b"""), 88),
            Cue(TransactionClass.CARD_PURCHASE, Regex("""تم خصم"""), 86),
            Cue(TransactionClass.PAYMENT, Regex("""(?i)\b(payment\s+of|payment\s+to|bill\s+payment|paid)\b|تم دفع"""), 84),
        )
    }
}

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
        val balanceCue = Regex("""(?i)(balance|الرصيد)""")
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
        val maskPattern = Regex("""(?i)\b(?:card|account|acct)\s+ending\s+\**(\d{4})\b|\bending\s+(\d{4})\b""")
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
        if (classification.type == TransactionClass.CARD_PURCHASE && !purchaseMovement.containsMatchIn(folded)) {
            reasons += "purchase_without_movement"
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
        val purchaseMovement = Regex(
            """(?i)\b(charged|debited|purchased|purchase\s+of|purchase\s+at|card\s+purchase|spent)\b|تم خصم""",
        )
    }
}
