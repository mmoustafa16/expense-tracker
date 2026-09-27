package expense.ingest.fixture

import expense.money.Currency
import expense.money.MoneyText
import expense.parse.AccountKind
import expense.parse.BankProfile
import expense.parse.DayMonthYear
import expense.parse.Direction
import expense.parse.Extraction
import expense.parse.ExtractorOutcome
import expense.parse.SmsTemplate
import expense.parse.TemplateExtractor
import expense.parse.TemplateLanguage
import expense.parse.TransactionCandidate
import expense.parse.TransactionKind

/**
 * Test-only bank. It is not a CBE-licensed institution and must not be
 * registered in [expense.parse.BankRegistry.EMPTY].
 */
object SyntheticBankProfile {
    const val ID: String = "example.test-bank"
    const val VERSION: String = "1"
    const val SENDER: String = "TESTBANK"

    fun create(
        id: String = ID,
        version: String = VERSION,
        senderIds: Set<String> = setOf(SENDER),
    ): BankProfile {
        return BankProfile(
            id = id,
            version = version,
            displayName = "Example Test Bank",
            senderIds = senderIds,
            templates = listOf(
                template("en-purchase", setOf(TemplateLanguage.EN), ::englishPurchase),
                template("ar-purchase", setOf(TemplateLanguage.AR), ::arabicPurchase),
                template("mixed-purchase", setOf(TemplateLanguage.MIXED), ::mixedPurchase),
                template("purchase-fee", setOf(TemplateLanguage.EN), ::purchaseWithFee),
                template("refund", setOf(TemplateLanguage.EN), ::refund),
                template("reversal", setOf(TemplateLanguage.EN), ::reversal),
                template("failed", setOf(TemplateLanguage.EN), ::failedPayment),
                template("transfer-out", setOf(TemplateLanguage.EN), ::transferOut),
                template("transfer-in", setOf(TemplateLanguage.EN), ::transferIn),
                template("income", setOf(TemplateLanguage.EN), ::income),
                template("cash", setOf(TemplateLanguage.EN), ::cashWithdrawal),
                template("installment", setOf(TemplateLanguage.EN), ::installment),
                template("foreign", setOf(TemplateLanguage.EN), ::foreignPurchase),
                template("maybe", setOf(TemplateLanguage.EN), ::lowConfidence),
                template("boom", setOf(TemplateLanguage.EN), ::boom),
                template("no-amount", setOf(TemplateLanguage.EN), ::missingAmount),
            ),
        )
    }

    private fun template(
        id: String,
        languages: Set<TemplateLanguage>,
        extract: (List<String>) -> ExtractorOutcome,
    ): SmsTemplate {
        return SmsTemplate(
            id = id,
            languages = languages,
            minimumConfidence = 80,
            extractor = TemplateExtractor { sms ->
                val parts = sms.body.split('|')
                if (parts.size < 8) {
                    ExtractorOutcome.NoMatch
                } else {
                    extract(parts)
                }
            },
        )
    }

    private fun englishPurchase(parts: List<String>): ExtractorOutcome {
        return single(parts, "purchase", TransactionKind.PURCHASE, Direction.DEBIT)
    }

    private fun arabicPurchase(parts: List<String>): ExtractorOutcome {
        if (parts[0] != "تيست" || parts[1] != "شراء") return ExtractorOutcome.NoMatch
        return matched(parts, TransactionKind.PURCHASE, Direction.DEBIT, confidence = 95)
    }

    private fun mixedPurchase(parts: List<String>): ExtractorOutcome {
        return single(parts, "mixed", TransactionKind.PURCHASE, Direction.DEBIT)
    }

    private fun purchaseWithFee(parts: List<String>): ExtractorOutcome {
        if (!isKind(parts, "purchase_fee")) return ExtractorOutcome.NoMatch
        val purchase = candidate(parts, TransactionKind.PURCHASE, Direction.DEBIT, parts[3])
        val feeAmount = parts.getOrNull(8).orEmpty()
        val fee = candidate(parts, TransactionKind.FEE, Direction.DEBIT, feeAmount).copy(merchantRaw = "Fee")
        return ExtractorOutcome.Matched(Extraction(95, listOf(purchase, fee)))
    }

    private fun refund(parts: List<String>): ExtractorOutcome {
        return single(parts, "refund", TransactionKind.REFUND, Direction.CREDIT)
    }

    private fun reversal(parts: List<String>): ExtractorOutcome {
        return single(parts, "reversal", TransactionKind.REVERSAL, Direction.CREDIT)
    }

    private fun failedPayment(parts: List<String>): ExtractorOutcome {
        return single(parts, "failed", TransactionKind.FAILED, Direction.DEBIT)
    }

    private fun transferOut(parts: List<String>): ExtractorOutcome {
        return single(parts, "transfer_out", TransactionKind.TRANSFER_OUT, Direction.DEBIT, AccountKind.ACCOUNT)
    }

    private fun transferIn(parts: List<String>): ExtractorOutcome {
        return single(parts, "transfer_in", TransactionKind.TRANSFER_IN, Direction.CREDIT, AccountKind.ACCOUNT)
    }

    private fun income(parts: List<String>): ExtractorOutcome {
        return single(parts, "income", TransactionKind.INCOME, Direction.CREDIT, AccountKind.ACCOUNT)
    }

    private fun cashWithdrawal(parts: List<String>): ExtractorOutcome {
        return single(parts, "cash", TransactionKind.CASH_WITHDRAWAL, Direction.DEBIT)
    }

    private fun installment(parts: List<String>): ExtractorOutcome {
        if (!isKind(parts, "installment")) return ExtractorOutcome.NoMatch
        val index = parts.getOrNull(8)?.toIntOrNull()
        val count = parts.getOrNull(9)?.toIntOrNull()
        val body = candidate(parts, TransactionKind.INSTALLMENT, Direction.DEBIT, parts[3]).copy(
            installmentIndex = index,
            installmentCount = count,
        )
        return ExtractorOutcome.Matched(Extraction(95, listOf(body)))
    }

    private fun foreignPurchase(parts: List<String>): ExtractorOutcome {
        if (!isKind(parts, "foreign")) return ExtractorOutcome.NoMatch
        val foreignCurrency = Currency.of(parts.getOrNull(8).orEmpty())
        val foreign = MoneyText.parse(parts.getOrNull(9).orEmpty(), foreignCurrency)
        val purchase = candidate(parts, TransactionKind.PURCHASE, Direction.DEBIT, parts[3]).copy(
            foreignAmount = foreign,
        )
        return ExtractorOutcome.Matched(Extraction(95, listOf(purchase)))
    }

    private fun lowConfidence(parts: List<String>): ExtractorOutcome {
        if (!isKind(parts, "maybe")) return ExtractorOutcome.NoMatch
        return matched(parts, TransactionKind.PURCHASE, Direction.DEBIT, confidence = 40)
    }

    private fun boom(parts: List<String>): ExtractorOutcome {
        if (!isKind(parts, "boom")) return ExtractorOutcome.NoMatch
        return ExtractorOutcome.Failed("synthetic extractor failure")
    }

    private fun missingAmount(parts: List<String>): ExtractorOutcome {
        if (!isKind(parts, "no_amount")) return ExtractorOutcome.NoMatch
        return matched(parts, TransactionKind.PURCHASE, Direction.DEBIT, confidence = 95)
    }

    private fun single(
        parts: List<String>,
        kindToken: String,
        kind: TransactionKind,
        direction: Direction,
        accountKind: AccountKind = AccountKind.DEBIT_CARD,
    ): ExtractorOutcome {
        if (!isKind(parts, kindToken)) return ExtractorOutcome.NoMatch
        return matched(parts, kind, direction, confidence = 95, accountKind = accountKind)
    }

    private fun matched(
        parts: List<String>,
        kind: TransactionKind,
        direction: Direction,
        confidence: Int,
        accountKind: AccountKind = AccountKind.DEBIT_CARD,
    ): ExtractorOutcome {
        return ExtractorOutcome.Matched(
            Extraction(
                confidence = confidence,
                candidates = listOf(candidate(parts, kind, direction, parts[3], accountKind)),
            ),
        )
    }

    private fun candidate(
        parts: List<String>,
        kind: TransactionKind,
        direction: Direction,
        amountText: String,
        accountKind: AccountKind = AccountKind.DEBIT_CARD,
    ): TransactionCandidate {
        val currency = Currency.of(parts[2])
        val mask = parts[5].takeIf { it.isNotBlank() }
        return TransactionCandidate(
            kind = kind,
            amount = amountText.takeIf { it.isNotBlank() }?.let { MoneyText.parse(it, currency) },
            direction = direction,
            merchantRaw = parts[4].takeIf { it.isNotBlank() },
            occurredAt = DayMonthYear.parse(parts[7]),
            reference = parts[6].takeIf { it.isNotBlank() },
            accountMask = mask,
            accountKind = if (mask == null) null else accountKind,
        )
    }

    private fun isKind(parts: List<String>, kindToken: String): Boolean {
        return parts[0] == "TB" && parts[1] == kindToken
    }
}
