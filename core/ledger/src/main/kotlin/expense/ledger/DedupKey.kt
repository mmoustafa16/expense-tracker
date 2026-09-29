package expense.ledger

import expense.money.Money
import expense.parse.FinancialEventType
import expense.sms.BodyHash

/**
 * Stable identity for corrections across a reparse.
 *
 * When a reference is present the key includes the event type, so a refund that cites
 * the original purchase reference stays a separate row and can be linked.
 * The fuzzy key is not an auto-merge rule.
 */
object DedupKey {
    fun of(
        institutionId: String,
        mask: String?,
        reference: String?,
        amount: Money,
        eventType: FinancialEventType,
        merchantKey: String?,
        minuteBucket: String,
    ): String {
        val material = if (!reference.isNullOrBlank()) {
            listOf("ref", institutionId, mask.orEmpty(), reference, eventType.name)
        } else {
            listOf(
                "fuzzy",
                institutionId,
                mask.orEmpty(),
                amount.amountMinor.toString(),
                amount.currency.code,
                eventType.name,
                merchantKey.orEmpty(),
                minuteBucket,
            )
        }
        return BodyHash.sha256(material.joinToString("|"))
    }
}
