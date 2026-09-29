package expense.intelligence

import expense.money.DigitFold
import expense.parse.AccountKind

/**
 * Finds the card or account identifier a message states.
 *
 * Institutions write the same fact in many shapes: `card ****4229`,
 * `card#4229`, `ending with ****4229`, `A/C XXXX4229`, `بطاقتك المنتهية
 * بـ4229`. Matching an ordered keyword-then-separator-then-digits template
 * fails the moment one institution reorders or abbreviates a word, so this
 * extractor works the other way round: it collects every short digit group in
 * the message, scores each one on the evidence around it, and keeps the best
 * candidate when that evidence clears a threshold.
 *
 * Scoring reads the grammar of the message. It never reads the sender, so a
 * new institution needs no entry anywhere.
 */
object PaymentInstruments {
    /** Digits an institution uses to identify an instrument. */
    private val MASK_LENGTHS = 3..6

    private const val ACCEPT_SCORE = 30

    /** How far back the scorer looks for the noun that names the instrument. */
    private const val CONTEXT_CHARS = 40

    /**
     * [claimed] are character ranges another extractor already explained, such
     * as the digits of an amount. A group inside one of them is not a candidate.
     */
    fun find(body: String, claimed: List<IntRange> = emptyList()): PaymentInstrument? {
        return candidates(body, claimed).firstOrNull { it.score >= ACCEPT_SCORE }?.instrument
    }

    fun candidates(body: String, claimed: List<IntRange> = emptyList()): List<ScoredInstrument> {
        val folded = DigitFold.fold(body)
        return digitGroups(folded)
            .filterNot { group -> claimed.any { group.start < it.last + 1 && it.first < group.end } }
            .mapNotNull { group -> score(folded, group) }
            .sortedWith(compareByDescending<ScoredInstrument> { it.score }.thenBy { it.start })
    }

    data class ScoredInstrument(
        val instrument: PaymentInstrument,
        val score: Int,
        val start: Int,
    )

    private data class DigitGroup(val text: String, val start: Int, val end: Int)

    /** Every run of digits, with its bounds. Longer runs are rejected later. */
    private fun digitGroups(folded: String): List<DigitGroup> {
        val groups = mutableListOf<DigitGroup>()
        var index = 0
        while (index < folded.length) {
            if (!folded[index].isDigit()) {
                index += 1
                continue
            }
            var end = index
            while (end < folded.length && folded[end].isDigit()) end += 1
            groups += DigitGroup(folded.substring(index, end), index, end)
            index = end
        }
        return groups
    }

    private fun score(folded: String, group: DigitGroup): ScoredInstrument? {
        if (group.text.length !in MASK_LENGTHS) return null
        if (partOfNumber(folded, group)) return null
        val prefix = folded.substring((group.start - CONTEXT_CHARS).coerceAtLeast(0), group.start)
        val immediate = prefix.takeLastWhile { it in MASK_CHARS || it == ' ' }
        val maskChars = immediate.count { it in MASK_CHARS }
        var score = 0
        if (maskChars >= 2) {
            score += 45
        } else if (maskChars == 1) {
            score += 25
        }
        val nounDistance = lastDistance(prefix, instrumentNoun)
        if (nounDistance != null) {
            score += if (nounDistance <= NEAR_CHARS) 35 else 20
        }
        if (lastDistance(prefix, identifierCue) != null) score += 20
        if (currencyBefore.containsMatchIn(prefix.takeLast(NEAR_CHARS))) score -= 60
        if (valueNoun.containsMatchIn(prefix.takeLast(NEAR_CHARS))) score -= 40
        if (otpCue.containsMatchIn(prefix)) score -= 60
        if (dateShaped(folded, group)) score -= 60
        if (score <= 0) return null
        val kind = kindOf(prefix)
        return ScoredInstrument(
            instrument = PaymentInstrument(mask = group.text, kind = kind),
            score = score,
            start = group.start,
        )
    }

    /** A group inside a decimal amount, a longer account number, or a time. */
    private fun partOfNumber(folded: String, group: DigitGroup): Boolean {
        val before = folded.getOrNull(group.start - 1)
        val after = folded.getOrNull(group.end)
        if (before == '.' || before == ',' || before == ':' || before == '/') return true
        if (after == '.' || after == ',' || after == ':' || after == '/') {
            return folded.getOrNull(group.end + 1)?.isDigit() == true
        }
        return false
    }

    /** A four-digit group that is a year in a written date. */
    private fun dateShaped(folded: String, group: DigitGroup): Boolean {
        if (group.text.length != 4) return false
        val window = folded.substring(
            (group.start - 12).coerceAtLeast(0),
            (group.end + 12).coerceAtMost(folded.length),
        )
        return datePattern.containsMatchIn(window)
    }

    private fun lastDistance(prefix: String, pattern: Regex): Int? {
        val match = pattern.findAll(prefix).lastOrNull() ?: return null
        return prefix.length - match.range.last - 1
    }

    private fun kindOf(prefix: String): AccountKind {
        val window = prefix.takeLast(CONTEXT_CHARS)
        return when {
            creditNoun.containsMatchIn(window) -> AccountKind.CREDIT_CARD
            debitNoun.containsMatchIn(window) -> AccountKind.DEBIT_CARD
            walletNoun.containsMatchIn(window) -> AccountKind.WALLET
            prepaidNoun.containsMatchIn(window) -> AccountKind.PREPAID
            accountNoun.containsMatchIn(window) -> AccountKind.ACCOUNT
            cardNoun.containsMatchIn(window) -> AccountKind.CARD
            else -> AccountKind.UNSPECIFIED
        }
    }

    private const val NEAR_CHARS = 20
    private val MASK_CHARS = setOf('*', 'x', 'X', '#', '\u2022', '\u25CF', '\u00B7', '\u06F4')

    /**
     * Nouns that name an instrument in either language. These are financial
     * vocabulary, not institution names, and the list does not grow when a new
     * bank is installed.
     */
    private val instrumentNoun = Regex(
        """(?i)\b(?:credit\s*card|debit\s*card|prepaid\s*card|card|account|acct|a/c|wallet)\b""" +
            """|بطاقة|بطاقتك|البطاقة|حساب|حسابك|الحساب|محفظة|محفظتك""",
    )

    /**
     * Words institutions put between the noun and the digits. Any subset in any
     * order still scores, which is what makes `ending with#4229` work.
     */
    private val identifierCue = Regex(
        """(?i)\b(?:ending|ends|end|no|no\.|num|number|xx|last|digits)\b""" +
            """|المنتهية|رقم|المنتهي|ينتهي""",
    )

    private val creditNoun = Regex("""(?i)\bcredit\s*card\b|بطاقة\s*ائتمان|ائتمانية|الائتمانية""")
    private val debitNoun = Regex("""(?i)\bdebit\s*card\b|بطاقة\s*خصم|بطاقة\s*الخصم""")
    private val walletNoun = Regex("""(?i)\bwallet\b|محفظة|محفظتك""")
    private val prepaidNoun = Regex("""(?i)\bprepaid\b|مسبقة\s*الدفع""")
    private val accountNoun = Regex("""(?i)\b(?:account|acct|a/c)\b|حساب|حسابك|الحساب""")
    private val cardNoun = Regex("""(?i)\bcard\b|بطاقة|بطاقتك|البطاقة""")
    /** Words that introduce a money value rather than an identifier. */
    private val valueNoun = Regex(
        """(?i)\b(?:balance|amount|limit|total|due|value|worth)\b|رصيد|الرصيد|مبلغ|المبلغ|قيمة|حد|الحد""",
    )
    private val currencyBefore = Regex("""(?i)\b(?:EGP|USD|EUR|GBP|SAR|AED|LE|L\.E\.?)\b|جنيه|جنية|دولار|يورو|ريال""")
    private val otpCue = Regex("""(?i)\b(?:otp|one[\s-]*time|verification|passcode|pin)\b|رمز|كلمة\s*المرور""")
    private val datePattern = Regex("""\d{1,2}[/\-]\d{1,2}[/\-]|[/\-]\d{1,2}[/\-]\d{1,2}""")
}

/**
 * Card or account identifier the SMS states. Kept for callers that want the
 * best candidate and nothing else.
 */
fun paymentInstrument(body: String): PaymentInstrument? = PaymentInstruments.find(body)
