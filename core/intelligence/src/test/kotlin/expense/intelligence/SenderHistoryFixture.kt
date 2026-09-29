package expense.intelligence

import expense.parse.AccountKind
import expense.parse.FinancialEventType

/**
 * The history a device has accumulated from a sender that has been reporting
 * completed money movements for a while.
 *
 * Tests about routing, extraction, or spend effect start from this state so the
 * channel is already verified and the assertion is about the thing under test
 * rather than about discovery. No sender is named in production code.
 */
fun establishedChannel(vararg senders: String): MutableSenderEvidenceLedger {
    return MutableSenderEvidenceLedger(
        senders.map { sender ->
            SenderEvidence(
                sender = sender,
                financialEvents = 6,
                movementEvents = 5,
                eventTypes = setOf(
                    FinancialEventType.CARD_PURCHASE,
                    FinancialEventType.BANK_TRANSFER,
                ),
                instrumentMasks = setOf("4229"),
                instrumentKinds = setOf(AccountKind.CREDIT_CARD),
            )
        },
    )
}
