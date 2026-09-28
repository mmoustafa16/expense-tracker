package expense.parse

import expense.money.DigitFold

/**
 * Retention gate only. A true result keeps the SMS for review.
 * It does not choose a bank, a kind, or an amount to post.
 *
 * A currency token next to a digit is not enough. Balances, payment reminders,
 * offers, greetings, and one-time passwords stay out of Review.
 */
object FinancialSignal {
    private val latinCurrency = Regex("""(?i)\b(EGP|USD|EUR|GBP|LE)\b""")
    private val arabicCurrencies = listOf("ج.م", "جنيه", "دولار", "يورو")
    private val oneTimeSecret = Regex(
        """(?i)(\botp\b|one[\s-]*time\s+(password|passcode|code|pin)|verification\s+code|security\s+code|رمز التحقق|كود التحقق|كلمة السر|كلمة المرور)""",
    )
    private val serviceNotice = Regex(
        """(?i)(\brecharg\w*\b|\btop[\s-]?ups?\b|\bairtime\b|\bmobile\s+balance\b|\bcredit\s+balance\b|\b(package|bundle|plan)\b.{0,40}\brenew\w*\b|\brenew\w*\b.{0,40}\b(package|bundle|plan)\b|\bsubscription\s+renew\w*\b)""",
    )
    private val completedMovement = listOf(
        Regex("""(?i)\bcharged\b"""),
        Regex("""(?i)\bdebited\b"""),
        Regex("""(?i)\bdeducted\b"""),
        Regex("""(?i)\bcredited\b"""),
        Regex("""(?i)\bwithdrawn\b"""),
        Regex("""(?i)\bwithdrawal\b"""),
        Regex("""(?i)\bpurchased\b"""),
        Regex("""(?i)\bpurchase\s+of\b"""),
        Regex("""(?i)\bpurchase\s+at\b"""),
        Regex("""(?i)\bpaid\b"""),
        Regex("""(?i)\bpayment\s+of\b"""),
        Regex("""(?i)\bpayment\s+to\b"""),
        Regex("""(?i)\bpayment\s+for\b"""),
        Regex("""(?i)\btransferred\b"""),
        Regex("""(?i)\btransfer\s+of\b"""),
        Regex("""(?i)\btransfer\s+to\b"""),
        Regex("""(?i)\bspent\b"""),
        Regex("""(?i)\bwas\s+used\s+for\b"""),
        Regex("""(?i)\btransaction\s+of\b"""),
    )
    private val arabicMovement = listOf(
        "تم خصم",
        "خصم مبلغ",
        "تم سحب",
        "سحب نقدي",
        "تم تحويل",
        "تحويل مبلغ",
        "تم دفع",
        "عملية شراء",
        "تم إضافة",
        "تم اضافة",
    )

    fun present(body: String): Boolean {
        val folded = DigitFold.fold(body)
        if (oneTimeSecret.containsMatchIn(folded)) return false
        if (serviceNotice.containsMatchIn(folded)) return false
        if (!mentionsMoney(folded)) return false
        if (completedMovement.any { it.containsMatchIn(folded) }) return true
        return arabicMovement.any { folded.contains(it) }
    }

    private fun mentionsMoney(folded: String): Boolean {
        val hasCurrency = latinCurrency.containsMatchIn(folded) ||
            arabicCurrencies.any { folded.contains(it) }
        return hasCurrency && folded.any { it.isDigit() }
    }
}
