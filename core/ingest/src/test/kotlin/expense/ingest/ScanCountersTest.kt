package expense.ingest

import expense.intelligence.SenderEvidence
import expense.parse.AccountKind
import expense.parse.FinancialEventType
import expense.parse.SpendEffect
import expense.sms.InboundSms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Each count says what it counts.
 *
 * The device showed the ledger claiming 2,344 "financial transactions" while
 * analytics totalled 1,809. Neither number was wrong: one was every stored SMS
 * that still had a body and the other was the rows analytics aggregates. They
 * were two different populations wearing one name. A count of messages is not a
 * count of events, a count of events is not a count of ledger rows, and a count
 * of ledger rows is not a count of spending.
 */
class ScanCountersTest {
    private val received = Instant.parse("2026-09-29T17:58:00Z")

    @Test
    fun `one scan reports five different populations that do not collapse into each other`() {
        val pipeline = IngestPipeline(ids = CounterIds())
        pipeline.preloadSenderEvidence(listOf(history))
        val inbox = listOf(
            CHANNEL to "Your appointment is confirmed for tomorrow",
            CHANNEL to "Save big this weekend with code SUMMER",
            CHANNEL to "Your available balance is EGP 1,250.00",
            CHANNEL to "Spent EGP 64.20 at Harbor Cafe using card ****4242",
            CHANNEL to "Spent EGP 12.00 at Harbor Bakery using card ****4242",
            CHANNEL to "Refund of EGP 20.00 from Harbor Cafe",
            CHANNEL to "Payment of EGP 500.00 received for your credit card ****4242",
            CHANNEL to "Your card ****4242 was charged at Harbor Cafe",
            "01005551234" to "Spent EGP 30.00 at Harbor Cafe using card ****4242",
        )
        var state = expense.ledger.LedgerState.empty()
        var tally = IngestTally()
        inbox.forEachIndexed { index, (sender, body) ->
            val result = pipeline.ingest(message(sender, body, "row-$index"), state)
            state = result.state
            tally = tally.add(result)
        }

        assertEquals(9, tally.smsScanned)
        assertEquals(7, tally.financialEvents)
        assertEquals(4, tally.postedTransactions)
        assertEquals(2, tally.reviewItems)
        assertEquals(3, tally.spendTransactions)
        assertEquals(1, tally.excludedFinancialEvents)
        assertEquals(
            tally.postedTransactions + tally.reviewItems + tally.excludedFinancialEvents,
            tally.financialEvents,
        )
        assertTrue(tally.smsScanned > tally.financialEvents)
        assertTrue(tally.financialEvents > tally.postedTransactions)
        assertTrue(tally.postedTransactions > tally.spendTransactions)
    }

    @Test
    fun `a settled credit card is a posted transaction that adds nothing to spending`() {
        val pipeline = IngestPipeline(ids = CounterIds())
        pipeline.preloadSenderEvidence(listOf(history))
        val result = pipeline.ingest(
            message(CHANNEL, "Payment of EGP 500.00 received for your credit card ****4242", "settle"),
        )
        assertTrue(result.posted)
        val row = result.state.transactions.single()
        assertEquals(FinancialEventType.CREDIT_CARD_PAYMENT, row.eventType)
        assertEquals(SpendEffect.LIABILITY_SETTLEMENT, row.spendEffect)
        assertFalse(row.includeInSpend)
        val tally = IngestTally().add(result)
        assertEquals(1, tally.postedTransactions)
        assertEquals(0, tally.spendTransactions)
    }

    /** What this device has watched the channel do before this scan. */
    private val history = SenderEvidence(
        sender = CHANNEL,
        financialEvents = 6,
        movementEvents = 5,
        eventTypes = setOf(FinancialEventType.CARD_PURCHASE, FinancialEventType.BANK_TRANSFER),
        instrumentMasks = setOf("4242"),
        instrumentKinds = setOf(AccountKind.CREDIT_CARD),
    )

    private fun message(sender: String, body: String, id: String): InboundSms {
        return InboundSms(
            sender = sender,
            body = body,
            providerMessageId = id,
            receivedAt = received,
        )
    }

    private companion object {
        const val CHANNEL = "HarbourBank"
    }
}

private class CounterIds : IdGenerator {
    private var next = 0

    override fun newId(): String = "counter-${next++}"
}
