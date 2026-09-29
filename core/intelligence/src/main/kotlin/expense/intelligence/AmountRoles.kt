package expense.intelligence

import expense.money.Currency
import expense.money.DigitFold
import expense.money.MoneyText
import expense.parse.AmountRole
import expense.parse.RoledAmount

/**
 * Splits a message into the clauses institutions actually write.
 *
 * A bank SMS states several facts in one string: what happened, what is left,
 * and sometimes what is on offer. Each fact has its own money value. Reading
 * the whole body as one context makes every value a candidate for every role,
 * which is what turns "amount plus remaining balance" into a false ambiguity.
 * Splitting on sentence and list punctuation keeps each value with the words
 * that describe it.
 */
object ClauseSegmenter {
    private val boundary = Regex("""[.;|\n\r!?]+|،|,|\s-\s|\bو(?=\s)""")

    data class Clause(val text: String, val start: Int, val end: Int)

    fun segment(text: String): List<Clause> {
        val clauses = mutableListOf<Clause>()
        var cursor = 0
        boundary.findAll(text).forEach { match ->
            if (match.range.first > cursor) {
                clauses += Clause(text.substring(cursor, match.range.first), cursor, match.range.first)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) clauses += Clause(text.substring(cursor), cursor, text.length)
        return clauses.ifEmpty { listOf(Clause(text, 0, text.length)) }
    }

    fun clauseOf(clauses: List<Clause>, position: Int): Clause? {
        return clauses.firstOrNull { position >= it.start && position < it.end }
    }
}

/**
 * Currency spellings and role vocabulary shared by every institution.
 *
 * These are the words for money itself, not the names or habits of any one
 * bank. A new institution using the same language is read by the same table,
 * and the table does not grow when one is installed.
 */
object MoneyLexicon {
    private val currencies: List<Pair<Regex, Currency>> = listOf(
        Regex("""(?i)\bEGP\b|\bL\.?E\.?\b|ج\.?م\.?|جنيهات?|جنيه|جنية""") to Currency.EGP,
        Regex("""(?i)\bUSD\b|\bUS\$|دولار""") to Currency.USD,
        Regex("""(?i)\bEUR\b|يورو""") to Currency.EUR,
        Regex("""(?i)\bGBP\b|إسترليني|استرليني""") to Currency.GBP,
        Regex("""(?i)\bSAR\b|ريال""") to Currency.of("SAR"),
        Regex("""(?i)\bAED\b|درهم""") to Currency.of("AED"),
    )

    /** Any currency token, used to locate money values before naming them. */
    const val CURRENCY_ALTERNATION: String =
        """\bEGP\b|\bL\.?E\.?\b|\bUSD\b|\bUS\$|\bEUR\b|\bGBP\b|\bSAR\b|\bAED\b""" +
            """|ج\.?م\.?|جنيهات?|جنيه|جنية|دولار|يورو|إسترليني|استرليني|ريال|درهم"""

    val currencyToken: Regex = Regex(CURRENCY_ALTERNATION, RegexOption.IGNORE_CASE)

    fun currencyOf(token: String): Currency? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null
        return currencies.firstOrNull { (pattern, _) -> pattern.matches(trimmed) }?.second
            ?: currencies.firstOrNull { (pattern, _) -> pattern.containsMatchIn(trimmed) }?.second
    }

    /**
     * Role vocabulary, most specific first. The tagger takes the first entry
     * whose cue appears in the clause holding the value.
     */
    val roleCues: List<Pair<AmountRole, Regex>> = listOf(
        AmountRole.MINIMUM_PAYMENT to Regex(
            """(?i)\bmin(?:imum)?\s+(?:payment|due|amount)\b|الحد\s*الأدنى""",
        ),
        AmountRole.AVAILABLE_CREDIT to Regex(
            """(?i)\bavailable\s+(?:credit|limit)\b|\bcredit\s+limit\b|\bremaining\s+limit\b""" +
                """|الحد\s*المتاح|الحد\s*الائتماني|المتاح\s*للبطاقة""",
        ),
        AmountRole.AVAILABLE_BALANCE to Regex(
            """(?i)\bavailable\s+balance\b|\bavbl\s*bal\b|\bavail\.?\s*bal\b|الرصيد\s*المتاح""",
        ),
        AmountRole.REMAINING_BALANCE to Regex(
            """(?i)\b(?:remaining|new|current|closing|running)\s+balance\b|\bbalance\s+(?:is|now|:)""" +
                """|\bbal\b|\bbalance\b""" +
                """|رصيد\s*حسابك|رصيدك|الرصيد\s*المتبقي|الرصيد\s*الحالي|المبلغ\s*المتبقي|الرصيد""",
        ),
        AmountRole.ORIGINAL_TRANSACTION_AMOUNT to Regex(
            """(?i)\boriginal\s+(?:amount|transaction|purchase)\b|المبلغ\s*الأصلي""",
        ),
        AmountRole.REFUND_AMOUNT to Regex(
            """(?i)\brefund(?:ed)?\b|\breturned\s+amount\b|مرتجع|استرداد|تم\s*رد""",
        ),
        AmountRole.TAX_AMOUNT to Regex("""(?i)\b(?:vat|tax|taxes)\b|ضريبة|الضريبة"""),
        AmountRole.FEE_AMOUNT to Regex(
            """(?i)\b(?:fee|fees|charge|charges|commission|service\s+charge)\b|رسوم|مصاريف|عمولة""",
        ),
        AmountRole.INSTALLMENT_AMOUNT to Regex("""(?i)\binstall?ment\b|\bmonthly\s+payment\b|قسط|القسط"""),
        AmountRole.PROMOTIONAL_AMOUNT to Regex(
            """(?i)\bup\s+to\b|\bas\s+(?:much|little)\s+as\b|\bget\s+up\s+to\b|\bwin\b|\bcashback\s+up\b""" +
                """|حتى|لحد|يصل\s*(?:إلى|الى|ل)|بحد\s*أقصى|أقصاه""",
        ),
        AmountRole.PAYMENT_AMOUNT to Regex(
            """(?i)\b(?:payment\s+of|paid|you\s+paid|settled|payment\s+received)\b""" +
                """|تم\s*سداد|سددت|تم\s*الدفع|تم\s*دفع""",
        ),
        AmountRole.TRANSACTION_AMOUNT to Regex(
            """(?i)\b(?:purchase|transaction|amount|amt|debited|credited|charged|withdrawn|withdrawal""" +
                """|spent|deducted|transferred|sent|received|renewed|recharge[d]?)\b""" +
                """|بمبلغ|مبلغ|تم\s*خصم|تم\s*شراء|تم\s*سحب|تم\s*تحويل|تم\s*إضافة|تم\s*تجديد|قيمة""",
        ),
    )
}

/**
 * Names every money value in a message from the clause that contains it.
 *
 * Unnamed values stay [AmountRole.UNKNOWN] rather than being guessed into the
 * transaction role, and the caller decides whether one of them may stand in
 * for a missing event value. The sender is never an input.
 */
class LexicalAmountRoleTagger : AmountRoleTagger {
    override fun tag(body: String, amounts: List<FoundAmount>): List<RoledAmount> {
        val clauses = ClauseSegmenter.segment(body)
        return amounts.map { found ->
            val clause = ClauseSegmenter.clauseOf(clauses, found.start)
            RoledAmount(
                amount = found.amount,
                role = roleIn(clause, found),
                token = found.token,
                currencyToken = found.currencyToken,
                start = found.start,
                end = found.end,
            )
        }
    }

    private fun roleIn(clause: ClauseSegmenter.Clause?, found: FoundAmount): AmountRole {
        if (clause == null) return AmountRole.UNKNOWN
        val before = clause.text.substring(0, (found.start - clause.start).coerceIn(0, clause.text.length))
        val cue = MoneyLexicon.roleCues.firstOrNull { (_, pattern) -> pattern.containsMatchIn(before) }
            ?: MoneyLexicon.roleCues.firstOrNull { (_, pattern) -> pattern.containsMatchIn(clause.text) }
        return cue?.first ?: AmountRole.UNKNOWN
    }
}

/**
 * Locates money values in a message. Finding a value and naming it are separate
 * steps so the span model can replace either one alone.
 */
object AmountScanner {
    /** Grouped thousands with an optional fraction, or a plain number. */
    private const val NUMBER = """[0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]+)?|[0-9]+(?:\.[0-9]+)?"""

    private val currencyFirst = Regex(
        """(${MoneyLexicon.CURRENCY_ALTERNATION})\s*($NUMBER)""",
        RegexOption.IGNORE_CASE,
    )
    private val numberFirst = Regex(
        """($NUMBER)\s*(${MoneyLexicon.CURRENCY_ALTERNATION})""",
        RegexOption.IGNORE_CASE,
    )

    fun scan(body: String): List<FoundAmount> {
        val folded = DigitFold.fold(body)
        val found = mutableListOf<FoundAmount>()
        collect(currencyFirst, folded, currencyGroup = 1, numberGroup = 2, into = found)
        collect(numberFirst, folded, currencyGroup = 2, numberGroup = 1, into = found)
        val ordered = found.sortedBy { it.start }
        val kept = mutableListOf<FoundAmount>()
        for (item in ordered) {
            if (kept.none { item.start < it.end && it.start < item.end }) kept += item
        }
        return kept
    }

    private fun collect(
        pattern: Regex,
        folded: String,
        currencyGroup: Int,
        numberGroup: Int,
        into: MutableList<FoundAmount>,
    ) {
        pattern.findAll(folded).forEach { hit ->
            val currencyToken = hit.groupValues[currencyGroup]
            val numberToken = hit.groupValues[numberGroup].trim()
            val currency = MoneyLexicon.currencyOf(currencyToken) ?: return@forEach
            val money = MoneyText.parse(numberToken, currency) ?: return@forEach
            into += FoundAmount(
                amount = money,
                token = numberToken,
                currencyToken = currencyToken,
                start = hit.range.first,
                end = hit.range.last + 1,
            )
        }
    }
}
