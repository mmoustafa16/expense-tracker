package expense.money

/**
 * Digit folding and explicit amount parsing for bank templates.
 *
 * The ingest pipeline does not call this to invent a transaction from an
 * unrecognized SMS. Templates opt in by passing a single amount field.
 */
object DigitFold {
    fun fold(input: String): String {
        val out = StringBuilder(input.length)
        for (ch in input) {
            out.append(
                when (ch) {
                    in '\u0660'..'\u0669' -> '0' + (ch.code - '\u0660'.code)
                    in '\u06F0'..'\u06F9' -> '0' + (ch.code - '\u06F0'.code)
                    '\u066B' -> '.'
                    '\u066C' -> ','
                    else -> ch
                },
            )
        }
        return out.toString()
    }
}

object MoneyText {
    private val amountToken = Regex("""\d[\d .,\u00A0]*\d|\d""")

    fun parse(text: String, currency: Currency): Money? {
        val folded = DigitFold.fold(text)
        val token = amountToken.find(folded)?.value ?: return null
        val minor = minorUnits(token, currency.minorUnits) ?: return null
        return Money(minor, currency)
    }

    private fun minorUnits(token: String, scale: Int): Long? {
        val compact = token.filterNot { it == ' ' || it == '\u00A0' }
        if (compact.isEmpty()) return null
        val dot = compact.lastIndexOf('.')
        val comma = compact.lastIndexOf(',')
        val separator = maxOf(dot, comma)
        if (separator < 0) {
            val whole = compact.toLongOrNull() ?: return null
            return scaleWhole(whole, scale)
        }
        val fraction = compact.substring(separator + 1)
        if (fraction.isEmpty() || fraction.any { !it.isDigit() }) return null
        val wholePart = compact.substring(0, separator).replace(".", "").replace(",", "")
        if (wholePart.isEmpty() || wholePart.any { !it.isDigit() }) return null
        val bothSeparators = dot >= 0 && comma >= 0
        val grouping = !bothSeparators && fraction.length == 3 && scale != 3
        if (grouping) {
            val digits = compact.replace(".", "").replace(",", "")
            val whole = digits.toLongOrNull() ?: return null
            return scaleWhole(whole, scale)
        }
        if (fraction.length > scale) return null
        val combined = wholePart + fraction.padEnd(scale, '0')
        return combined.toLongOrNull()
    }

    private fun scaleWhole(whole: Long, scale: Int): Long? {
        return try {
            var value = whole
            repeat(scale) {
                value = Math.multiplyExact(value, 10L)
            }
            value
        } catch (_: ArithmeticException) {
            null
        }
    }
}
