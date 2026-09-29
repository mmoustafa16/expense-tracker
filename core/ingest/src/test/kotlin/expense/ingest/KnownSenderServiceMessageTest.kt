package expense.ingest

import expense.intelligence.DiscoveryStatus
import expense.intelligence.FinancialSmsIntelligence
import expense.intelligence.InstitutionBootstrap
import expense.intelligence.RegisteredSender
import expense.intelligence.SmsText
import expense.money.Currency
import expense.money.Money
import expense.parse.Direction
import expense.parse.FinancialEventType
import expense.parse.ParseStatus
import expense.parse.SpendEffect
import expense.parse.VerifiedBankCatalog
import expense.sms.InboundSms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class KnownSenderServiceMessageTest {
    private val wallet = RegisteredSender(
        "example.vodafone-cash",
        "Vodafone Cash",
        setOf("Vodafone", "VodafoneCash"),
    )
    private val intelligence = FinancialSmsIntelligence.deterministic(listOf(wallet))
    private val pipeline = IngestPipeline(ids = ServiceIds(), intelligence = intelligence)

    @Test
    fun `a known wallet sender does not put balance code or promo messages in review`() {
        val ignored = listOf(
            "Your mobile balance is EGP 15.50",
            "Special offer just for you. Use code SAVE20",
            "Your OTP is 482193",
            "Your security code is 119900",
            "Your available balance is EGP 1,250.00",
            "Account balance EGP 80.00",
        )
        var state = pipeline.ingest(message("Vodafone", ignored.first(), "s0")).state
        ignored.drop(1).forEachIndexed { index, body ->
            val sender = if (index % 2 == 0) "VodafoneCash" else "Vodafone"
            state = pipeline.ingest(message(sender, body, "s${index + 1}"), state).state
        }
        assertTrue(state.attempts.all { it.status == ParseStatus.IGNORED_NOT_BANK })
        assertTrue(state.messages.all { it.body == null })
        assertTrue(state.reviewQueue().isEmpty())
        assertTrue(state.transactions.isEmpty())
        assertTrue(InstitutionBootstrap.records.none { "Vodafone" in it.senderIds || "VodafoneCash" in it.senderIds })
        assertTrue(VerifiedBankCatalog.registry().profiles.isEmpty())

        val known = intelligence.assess(SmsText("Vodafone", "Your mobile balance is EGP 15.50"))
        val unknown = FinancialSmsIntelligence.deterministic().assess(SmsText("Vodafone", "Your mobile balance is EGP 15.50"))
        assertEquals(FinancialEventType.BALANCE_NOTIFICATION, known.classification.eventType)
        assertEquals(known.classification.eventType, unknown.classification.eventType)
        assertEquals(DiscoveryStatus.KNOWN, known.discovery.status)
        assertEquals("example.vodafone-cash", known.discovery.verifiedInstitution?.institutionId)
        assertEquals(DiscoveryStatus.KNOWN, unknown.discovery.status)
        assertNull(unknown.discovery.verifiedInstitution)
        assertFalse(known.postable)
        assertFalse(unknown.postable)
    }

    /**
     * A package renewal that took money is a financial event, not chatter. It
     * belongs in the ledger with the effect the message supports, which is what
     * separates "we did not understand it" from "it is not spending".
     */
    @Test
    fun `a paid service charge is a financial event with its own spend effect`() {
        val paid = pipeline.ingest(message("Vodafone", "You paid EGP 30 to renew your monthly package", "p1"))
        assertEquals(ParseStatus.PARSED, paid.status)
        assertEquals(FinancialEventType.BILL_PAYMENT, paid.state.transactions.single().eventType)
        assertEquals(SpendEffect.SPEND, paid.state.transactions.single().spendEffect)
        assertTrue(paid.state.transactions.single().includeInSpend)

        val recharge = pipeline.ingest(
            message("VodafoneCash", "Recharge successful. You recharged EGP 50", "p2"),
            paid.state,
        )
        assertEquals(ParseStatus.PARSED, recharge.status)
        val row = recharge.state.transactions.last()
        assertEquals(FinancialEventType.OTHER_FINANCIAL, row.eventType)
        assertEquals(SpendEffect.NONE, row.spendEffect)
        assertFalse(row.includeInSpend)
    }

    @Test
    fun `the same known sender still keeps confirmed money movements`() {
        val purchase = pipeline.ingest(message("Vodafone", "Your card was used for EGP 450 at Talabat", "p"))
        assertEquals(ParseStatus.PARSED, purchase.status)
        assertTrue(purchase.posted)
        assertEquals(FinancialEventType.CARD_PURCHASE, purchase.state.transactions.single().eventType)
        assertEquals(Money(45000, Currency.EGP), purchase.state.transactions.single().amount)

        val transfer = pipeline.ingest(
            message("VodafoneCash", "Transferred EGP 120.00 to Sam", "t"),
            purchase.state,
        )
        assertEquals(ParseStatus.PARSED, transfer.status)
        assertEquals(FinancialEventType.BANK_TRANSFER, transfer.state.transactions.last().eventType)
        assertEquals(Direction.DEBIT, transfer.state.transactions.last().direction)

        val withdrawal = pipeline.ingest(
            message("Vodafone", "Cash withdrawal of EGP 200.00 at ATM", "w"),
            transfer.state,
        )
        assertEquals(ParseStatus.PARSED, withdrawal.status)
        assertEquals(FinancialEventType.CASH_WITHDRAWAL, withdrawal.state.transactions.last().eventType)

        val refund = pipeline.ingest(
            message("VodafoneCash", "Refund of EGP 75.00 from Shop", "r"),
            withdrawal.state,
        )
        assertEquals(ParseStatus.PARSED, refund.status)
        assertEquals(FinancialEventType.REFUND, refund.state.transactions.last().eventType)

        val reversal = pipeline.ingest(
            message("Vodafone", "Reversed EGP 30.00 at Shop", "v"),
            refund.state,
        )
        assertEquals(ParseStatus.PARSED, reversal.status)
        assertEquals(FinancialEventType.REVERSAL, reversal.state.transactions.last().eventType)

        val fee = pipeline.ingest(
            message("VodafoneCash", "A service fee of EGP 5.00 was applied", "f"),
            reversal.state,
        )
        assertEquals(ParseStatus.PARSED, fee.status)
        assertEquals(FinancialEventType.FEE, fee.state.transactions.last().eventType)
        assertTrue(fee.state.reviewQueue().isEmpty())
        assertEquals(6, fee.state.transactions.size)
    }

    @Test
    fun `an unknown sender uses the same classification and a purchase stays in review`() {
        val open = IngestPipeline(ids = ServiceIds())
        val promo = open.ingest(message("NEWS", "Special offer just for you. Use code SAVE20", "n1"))
        assertEquals(ParseStatus.IGNORED_NOT_BANK, promo.status)
        assertNull(promo.state.messages.single().body)
        assertTrue(promo.state.reviewQueue().isEmpty())

        val purchase = open.ingest(message("01005551234", "Your card was used for EGP 450 at Talabat", "n2"))
        assertEquals(ParseStatus.UNSUPPORTED, purchase.status)
        assertEquals("unknown_institution", purchase.attempt?.error)
        assertFalse(purchase.posted)
        assertEquals(1, purchase.state.reviewQueue().size)
        assertTrue(purchase.state.transactions.isEmpty())
    }

    private fun message(sender: String, body: String, id: String): InboundSms {
        return InboundSms(
            sender = sender,
            body = body,
            providerMessageId = id,
            receivedAt = Instant.parse("2026-04-04T08:00:00Z"),
        )
    }
}

private class ServiceIds : IdGenerator {
    private var next = 0

    override fun newId(): String = "svc-${next++}"
}
