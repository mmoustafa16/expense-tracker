package expense.parse

import expense.money.DigitFold

/**
 * Retention gate only. A true result keeps the SMS for review.
 * It does not choose a bank, a kind, or an amount to post.
 */
object FinancialSignal {
    private val latinCurrency = Regex("""(?i)\b(EGP|USD|EUR|GBP|LE)\b""")
    private val arabicCurrencies = listOf("ج.م", "جنيه", "دولار", "يورو")

    fun present(body: String): Boolean {
        val folded = DigitFold.fold(body)
        val hasCurrency = latinCurrency.containsMatchIn(folded) ||
            arabicCurrencies.any { folded.contains(it) }
        val hasDigit = folded.any { it.isDigit() }
        return hasCurrency && hasDigit
    }
}
