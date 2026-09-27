package expense.android.ui.review

import expense.android.ui.common.MoneyFormat
import expense.android.ui.common.UiResult
import expense.categories.Category
import expense.categories.CategoryCatalog
import expense.ingest.CairoClock
import expense.ingest.PipelineMetadata
import expense.ledger.ManualDraft
import expense.money.Currency
import expense.money.Money
import expense.money.MoneyText
import expense.parse.AccountKind
import expense.parse.Direction
import expense.parse.ParseAttempt
import expense.parse.TransactionKind
import java.time.LocalDateTime

data class ManualEntry(
    val amountText: String,
    val currencyCode: String,
    val direction: Direction,
    val kind: TransactionKind,
    val occurredCivil: LocalDateTime,
    val merchantRaw: String,
    val categoryId: String?,
    val reference: String,
    val institutionLabel: String,
    val accountKind: AccountKind?,
    val accountMask: String,
    val reviewAttemptId: String?,
    val pipelineVersion: String,
)

/**
 * Builds a [ManualDraft]. The draft has no bank profile and this type never
 * reads or writes one.
 */
object ManualEntries {
    fun fromReview(attempt: ParseAttempt?, now: LocalDateTime): ManualEntry {
        val candidate = attempt?.extraction?.candidates?.firstOrNull()
        return ManualEntry(
            amountText = candidate?.amount?.let(::amountField).orEmpty(),
            currencyCode = candidate?.amount?.currency?.code ?: Currency.EGP.code,
            direction = candidate?.direction ?: Direction.DEBIT,
            kind = candidate?.kind ?: TransactionKind.PURCHASE,
            occurredCivil = candidate?.occurredAt ?: now.withSecond(0).withNano(0),
            merchantRaw = candidate?.merchantRaw.orEmpty(),
            categoryId = null,
            reference = candidate?.reference.orEmpty(),
            institutionLabel = "",
            accountKind = candidate?.accountKind,
            accountMask = candidate?.accountMask.orEmpty(),
            reviewAttemptId = attempt?.id,
            pipelineVersion = attempt?.pipelineVersion ?: PipelineMetadata.VERSION,
        )
    }

    fun draft(entry: ManualEntry, categories: List<Category>): UiResult<ManualDraft> {
        val institution = entry.institutionLabel.trim()
        if (institution.isEmpty()) {
            return UiResult.Rejected("Enter a bank label. This does not add a bank parser.")
        }
        val code = entry.currencyCode.trim()
        if (code.length != 3 || code.any { !it.isLetter() }) {
            return UiResult.Rejected("Enter a three-letter currency code.")
        }
        val currency = try {
            Currency.of(code)
        } catch (_: IllegalArgumentException) {
            return UiResult.Rejected("Enter a three-letter currency code.")
        }
        val amount = MoneyText.parse(entry.amountText.trim(), currency)
            ?: return UiResult.Rejected("Enter an amount.")
        val mask = entry.accountMask.trim()
        val accountKind = entry.accountKind
        if ((accountKind == null) != mask.isEmpty()) {
            return UiResult.Rejected("An account needs both a kind and a mask, or neither.")
        }
        val requestedCategory = entry.categoryId?.trim()?.takeIf { it.isNotEmpty() }
        val categoryId = if (requestedCategory == null) {
            null
        } else {
            CategoryCatalog.canonicalId(requestedCategory, categories)
                ?: return UiResult.Rejected("Choose a category from the ledger.")
        }
        return UiResult.Ready(
            ManualDraft(
                amount = amount,
                direction = entry.direction,
                kind = entry.kind,
                occurredAt = CairoClock.instantFrom(entry.occurredCivil),
                occurredCivil = entry.occurredCivil,
                merchantRaw = entry.merchantRaw.trim().ifEmpty { null },
                categoryId = categoryId,
                reference = entry.reference.trim().ifEmpty { null },
                institutionId = institution,
                accountKind = accountKind,
                accountMask = mask.ifEmpty { null },
                reviewAttemptId = entry.reviewAttemptId?.trim()?.ifEmpty { null },
                pipelineVersion = entry.pipelineVersion.ifBlank { PipelineMetadata.VERSION },
            ),
        )
    }

    private fun amountField(money: Money): String = MoneyFormat.format(money).substringBefore(' ')
}
