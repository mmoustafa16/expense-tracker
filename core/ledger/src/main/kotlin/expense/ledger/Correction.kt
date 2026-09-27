package expense.ledger

import java.time.Instant

enum class CorrectionField {
    MERCHANT,
    CATEGORY,
    KIND,
    AMOUNT,
    ACCOUNT,
    INCLUDE_IN_SPEND,
}

/**
 * A ledger overlay. Applying a correction never creates or edits a [expense.parse.BankProfile].
 */
data class Correction(
    val id: String,
    val dedupKey: String,
    val field: CorrectionField,
    val previousValue: String?,
    val updatedValue: String?,
    val applyForward: Boolean,
    val createdAt: Instant,
)
