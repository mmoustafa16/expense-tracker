package expense.android.ui.ledger

import expense.categories.CategorySeed
import expense.ledger.Account
import expense.ledger.LedgerState
import expense.ledger.Transaction
import expense.money.Currency
import expense.parse.AccountKind
import expense.parse.Direction
import expense.parse.FinancialEventType
import expense.parse.SpendEffect
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TransactionCard(
    val id: String,
    val title: String,
    val amountLine: String,
    val channelLine: String,
    val whenLine: String,
    val category: String?,
)

data class LedgerView(
    val scopeLabel: String,
    val months: List<YearMonth>,
    val accounts: List<Account>,
    val cards: List<TransactionCard>,
)

object LedgerCards {
    fun view(state: LedgerState, month: YearMonth?, accountId: String?): LedgerView {
        val months = state.transactions
            .map { YearMonth.from(it.occurredCivil) }
            .distinct()
            .sortedDescending()
        val accounts = state.accounts.sortedWith(compareBy({ it.institutionId }, { it.mask }, { it.id }))
        val cards = state.transactions
            .filter { month == null || YearMonth.from(it.occurredCivil) == month }
            .filter { accountId == null || it.accountId == accountId }
            .sortedWith(compareByDescending<Transaction> { it.occurredCivil }.thenBy { it.id })
            .map { transaction -> card(state, transaction) }
        return LedgerView(
            scopeLabel = scopeLabel(state, month, accountId),
            months = months,
            accounts = accounts,
            cards = cards,
        )
    }

    fun scopeLabel(state: LedgerState, month: YearMonth?, accountId: String?): String {
        val account = accountId?.let { id -> state.accounts.find { it.id == id } }
        val monthLabel = month?.format(MONTH)
        val accountLabel = account?.let { channel(state, account = it, mask = true) }
        return when {
            monthLabel == null && accountLabel == null -> "All transactions"
            monthLabel != null && accountLabel != null -> "$monthLabel · $accountLabel"
            monthLabel != null -> monthLabel
            else -> accountLabel.orEmpty()
        }
    }

    private fun card(state: LedgerState, transaction: Transaction): TransactionCard {
        val merchant = state.merchants.find { it.id == transaction.merchantId }?.displayName
        val title = merchant?.takeIf { it.isNotBlank() }
            ?: transaction.merchantRaw?.takeIf { it.isNotBlank() }
            ?: eventTitle(transaction.eventType)
        val account = transaction.accountId?.let { id -> state.accounts.find { it.id == id } }
        return TransactionCard(
            id = transaction.id,
            title = title,
            amountLine = "${amount(transaction)} · ${direction(transaction.direction)}",
            channelLine = channel(state, transaction, account),
            whenLine = `when`(transaction),
            category = categoryName(state, transaction.categoryId),
        )
    }

    private fun channel(state: LedgerState, transaction: Transaction, account: Account?): String {
        val institution = institutionLabel(state, transaction)
        val kind = account?.kind?.takeUnless { it == AccountKind.UNSPECIFIED }?.readable()
        val mask = account?.mask?.takeIf { it.isNotBlank() }?.let { "••••$it" }
        return listOfNotNull(institution, kind, mask).joinToString(" · ").ifBlank { "Unknown" }
    }

    private fun channel(state: LedgerState, account: Account, mask: Boolean): String {
        val sample = state.transactions.firstOrNull { it.accountId == account.id }
        val institution = sample?.let { institutionLabel(state, it) } ?: account.institutionId
        val kind = account.kind.takeUnless { it == AccountKind.UNSPECIFIED }?.readable()
        val digits = if (mask && account.mask.isNotBlank()) "••••${account.mask}" else null
        return listOfNotNull(institution, kind, digits).joinToString(" · ")
    }

    private fun institutionLabel(state: LedgerState, transaction: Transaction): String {
        val sender = state.messages.find { it.id == transaction.smsId }?.sender?.trim().orEmpty()
        return sender.ifBlank { transaction.institutionId.ifBlank { "Unknown" } }
    }

    private fun categoryName(state: LedgerState, categoryId: String?): String? {
        if (categoryId.isNullOrBlank()) return null
        val custom = state.categories.find { it.id == categoryId || it.slug == categoryId }
        val seeded = CategorySeed.bySlug(categoryId)
        return custom?.nameEn ?: seeded?.nameEn
    }

    private fun amount(transaction: Transaction): String {
        return "${transaction.amount.currency.code} ${digits(transaction.amount.amountMinor, transaction.amount.currency)}"
    }

    private fun digits(absoluteMinor: Long, currency: Currency): String {
        val scale = currency.minorUnits
        if (scale == 0) return absoluteMinor.toString()
        var factor = 1L
        repeat(scale) { factor *= 10L }
        val whole = absoluteMinor / factor
        val fraction = (absoluteMinor % factor).toString().padStart(scale, '0')
        return "$whole.$fraction"
    }

    private fun direction(direction: Direction): String {
        return when (direction) {
            Direction.DEBIT -> "Debit"
            Direction.CREDIT -> "Credit"
        }
    }

    private fun `when`(transaction: Transaction): String {
        val date = transaction.occurredCivil.format(DATE)
        val time = transaction.occurredCivil.toLocalTime()
        return if (time == LocalTime.MIDNIGHT) date else "$date · ${time.format(TIME)}"
    }

    fun eventTitle(eventType: FinancialEventType): String {
        return when (eventType) {
            FinancialEventType.CARD_PURCHASE -> "Card purchase"
            FinancialEventType.INSTALLMENT -> "Installment"
            FinancialEventType.BANK_TRANSFER -> "Transfer"
            FinancialEventType.CASH_WITHDRAWAL -> "Cash withdrawal"
            FinancialEventType.CREDIT_CARD_PAYMENT -> "Credit card payment"
            FinancialEventType.BILL_PAYMENT -> "Payment"
            FinancialEventType.REFUND -> "Refund"
            FinancialEventType.REVERSAL -> "Reversal"
            FinancialEventType.FEE -> "Fee"
            FinancialEventType.INCOME -> "Income"
            FinancialEventType.BALANCE_NOTIFICATION -> "Balance"
            FinancialEventType.STATEMENT -> "Statement"
            FinancialEventType.PAYMENT_DUE -> "Payment due"
            FinancialEventType.FAILED_TRANSACTION -> "Failed"
            FinancialEventType.DECLINED_TRANSACTION -> "Declined"
            FinancialEventType.OTHER_FINANCIAL, FinancialEventType.NOT_FINANCIAL -> "Transaction"
        }
    }

    /** Says plainly why a posted row is not part of what the holder spent. */
    fun spendNote(spendEffect: SpendEffect): String? = when (spendEffect) {
        SpendEffect.SPEND, SpendEffect.SPEND_REVERSAL -> null
        SpendEffect.LIABILITY_SETTLEMENT -> "Settles a card balance, not spending"
        SpendEffect.TRANSFER_INTERNAL -> "Between your own accounts, not spending"
        SpendEffect.TRANSFER_EXTERNAL -> "Transfer, not counted as spending"
        SpendEffect.INCOME -> "Money in"
        SpendEffect.NONE -> "Not counted as spending"
    }

    private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
}
