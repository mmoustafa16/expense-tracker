package expense.merchants

import expense.money.DigitFold
import java.text.Normalizer
import java.util.Locale

object MerchantKey {
    private val processorPrefixes = setOf("visa", "mastercard", "meeza", "paypal", "sq")

    fun normalize(raw: String): String {
        val folded = DigitFold.fold(Normalizer.normalize(raw, Normalizer.Form.NFKC))
        val lowered = folded.lowercase(Locale.ROOT)
        val arabic = lowered
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
        val tokens = arabic
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .toMutableList()
        while (tokens.firstOrNull() in processorPrefixes) {
            tokens.removeAt(0)
        }
        if (tokens.size > 1 && tokens.last().all { it.isDigit() }) {
            tokens.removeAt(tokens.lastIndex)
        }
        return tokens.joinToString(" ")
    }
}
