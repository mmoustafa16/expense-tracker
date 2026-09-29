package expense.intelligence

import expense.parse.FinancialEventType

/**
 * On-device summary of ingested messages, grouped by sender.
 *
 * The report keeps the sender identifier, a shape label, class counts, and a
 * closed set of structural fingerprint tokens. It does not keep the SMS body,
 * an amount, a merchant, a card number, an OTP, or any other message text.
 * It does not post, and it does not change Review or the ledger.
 *
 * Nothing here is stored or sent off the device. Callers must not log the
 * input messages.
 */
class SenderDiscoveryDiagnostic(
    private val intelligence: FinancialSmsIntelligence = FinancialSmsIntelligence.deterministic(),
) {
    fun summarize(messages: List<DiagnosticSms>): SenderDiagnosticReport {
        val grouped = linkedMapOf<String, MutableSender>()
        for (message in messages) {
            val sender = message.sender.trim()
            val bucket = grouped.getOrPut(sender) { MutableSender(sender) }
            bucket.count += 1
            val body = message.body
            val fingerprint = MessageFingerprint.of(body)
            bucket.fingerprints.merge(fingerprint, 1, Int::plus)
            if (body == null) {
                bucket.classes.merge(NOT_RETAINED, 1, Int::plus)
            } else {
                val eventType = intelligence.assess(SmsText(sender, body)).classification.eventType
                bucket.classes.merge(eventType.diagnosticLabel(), 1, Int::plus)
            }
        }
        val senders = grouped.values.map { it.freeze() }.sortedWith(
            compareByDescending<SenderSummary> { it.messageCount }.thenBy { it.sender },
        )
        return SenderDiagnosticReport(senders)
    }

    private class MutableSender(val sender: String) {
        var count: Int = 0
        val classes: MutableMap<String, Int> = linkedMapOf()
        val fingerprints: MutableMap<String, Int> = linkedMapOf()

        fun freeze(): SenderSummary {
            return SenderSummary(
                sender = sender,
                messageCount = count,
                shape = senderAddressShape(sender).diagnosticLabel(),
                classCounts = classes.toSortedMap(),
                fingerprints = fingerprints.toSortedMap(),
            )
        }
    }

    private companion object {
        const val NOT_RETAINED: String = "not_retained"
    }
}

data class DiagnosticSms(
    val sender: String,
    val body: String?,
)

data class SenderSummary(
    val sender: String,
    val messageCount: Int,
    val shape: String,
    val classCounts: Map<String, Int>,
    val fingerprints: Map<String, Int>,
) {
    fun lines(): List<String> {
        return listOf(
            "$sender · $shape · $messageCount",
            classCounts.entries.joinToString(" · ") { "${it.key} ${it.value}" },
            fingerprints.entries.joinToString(" · ") { "${it.key} ${it.value}" },
        )
    }
}

data class SenderDiagnosticReport(
    val senders: List<SenderSummary>,
) {
    fun lines(): List<String> {
        if (senders.isEmpty()) return listOf("No ingested messages.")
        val total = senders.sumOf { it.messageCount }
        return listOf("senders ${senders.size} · messages $total") + senders.flatMap { it.lines() }
    }
}

/** Structural tokens only. Match text is never copied into the fingerprint. */
object MessageFingerprint {
    val tokens: Set<String> = setOf(
        "EMPTY",
        "NOT_RETAINED",
        "PROSE",
        "PIPE_DELIMITED",
        "AMOUNT_PRESENT",
        "MASK_PRESENT",
        "DATE_PRESENT",
        "TIME_PRESENT",
        "REFERENCE_CUE",
        "OTP_CUE",
        "BALANCE_CUE",
    )

    fun of(body: String?): String {
        if (body == null) return "NOT_RETAINED"
        if (body.isBlank()) return "EMPTY"
        val found = mutableListOf("PROSE")
        if (body.count { it == '|' } >= 2) found += "PIPE_DELIMITED"
        if (currency.containsMatchIn(body)) found += "AMOUNT_PRESENT"
        if (mask.containsMatchIn(body)) found += "MASK_PRESENT"
        if (date.containsMatchIn(body)) found += "DATE_PRESENT"
        if (time.containsMatchIn(body)) found += "TIME_PRESENT"
        if (referenceCue.containsMatchIn(body)) found += "REFERENCE_CUE"
        if (otpCue.containsMatchIn(body)) found += "OTP_CUE"
        if (balanceCue.containsMatchIn(body)) found += "BALANCE_CUE"
        return found.joinToString("+")
    }

    private val currency = Regex("""(?i)\b(EGP|USD|EUR|GBP|LE)\b|جنيه|دولار|يورو""")
    private val mask = Regex("""(?i)\b(?:card|account|acct)\s+ending\s+\**\d{4}\b|\bending\s+\d{4}\b""")
    private val date = Regex("""\b\d{2}/\d{2}/\d{4}\b""")
    private val time = Regex("""\b\d{2}:\d{2}\b""")
    private val referenceCue = Regex("""(?i)\bref(?:erence)?\b""")
    private val otpCue = Regex("""(?i)\botp\b|one[\s-]*time""")
    private val balanceCue = Regex("""(?i)\bbalance\b|الرصيد""")
}

fun FinancialEventType.diagnosticLabel(): String = when (this) {
    FinancialEventType.CARD_PURCHASE -> "purchase"
    FinancialEventType.BANK_TRANSFER -> "transfer"
    FinancialEventType.CASH_WITHDRAWAL -> "withdrawal"
    FinancialEventType.CREDIT_CARD_PAYMENT -> "card_payment"
    FinancialEventType.BILL_PAYMENT -> "payment"
    FinancialEventType.REFUND -> "refund"
    FinancialEventType.REVERSAL -> "reversal"
    FinancialEventType.FEE -> "fee"
    FinancialEventType.INSTALLMENT -> "installment"
    FinancialEventType.INCOME -> "income"
    FinancialEventType.BALANCE_NOTIFICATION -> "balance"
    FinancialEventType.STATEMENT -> "statement"
    FinancialEventType.PAYMENT_DUE -> "payment_due"
    FinancialEventType.FAILED_TRANSACTION -> "failed"
    FinancialEventType.DECLINED_TRANSACTION -> "declined"
    FinancialEventType.OTHER_FINANCIAL -> "other_financial"
    FinancialEventType.NOT_FINANCIAL -> "not_financial"
}
