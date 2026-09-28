package expense.ingest

import expense.intelligence.DiscoveryStatus
import expense.intelligence.FinancialSmsIntelligence
import expense.intelligence.InstitutionBootstrap
import expense.intelligence.RegisteredSender
import expense.intelligence.SmsText
import expense.intelligence.TransactionClass
import expense.money.Currency
import expense.money.Money
import expense.parse.ParseStatus
import expense.parse.TransactionKind
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
    fun `a known wallet sender does not put service or promo messages in review`() {
        val ignored = listOf(
            "Your mobile balance is EGP 15.50",
            "Recharge successful. You recharged EGP 50",
            "Your package has been renewed for EGP 30",
            "You paid EGP 30 to renew your monthly package",
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
        assertTrue(InstitutionBootstrap.records.isEmpty())
        assertTrue(VerifiedBankCatalog.registry().profiles.isEmpty())

        val known = intelligence.assess(SmsText("Vodafone", "Your mobile balance is EGP 15.50"))
        val unknown = FinancialSmsIntelligence.deterministic().assess(SmsText("Vodafone", "Your mobile balance is EGP 15.50"))
        assertEquals(TransactionClass.BALANCE_NOTIFICATION, known.classification.type)
        assertEquals(known.classification.type, unknown.classification.type)
        assertEquals(DiscoveryStatus.KNOWN, known.discovery.status)
        assertEquals("example.vodafone-cash", known.discovery.verifiedInstitution?.institutionId)
        assertEquals(DiscoveryStatus.UNKNOWN, unknown.discovery.status)
        assertFalse(known.postable)
        assertFalse(unknown.postable)
    }

    @Test
    fun `the same known sender still keeps confirmed money movements`() {
        val purchase = pipeline.ingest(message("Vodafone", "Your card was used for EGP 450 at Talabat", "p"))
        assertEquals(ParseStatus.PARSED, purchase.status)
        assertTrue(purchase.posted)
        assertEquals(TransactionKind.PURCHASE, purchase.state.transactions.single().kind)
        assertEquals(Money(45000, Currency.EGP), purchase.state.transactions.single().amount)

        val transfer = pipeline.ingest(
            message("VodafoneCash", "Transferred EGP 120.00 to Sam", "t"),
            purchase.state,
        )
        assertEquals(ParseStatus.PARSED, transfer.status)
        assertEquals(TransactionKind.TRANSFER_OUT, transfer.state.transactions.last().kind)

        val withdrawal = pipeline.ingest(
            message("Vodafone", "Cash withdrawal of EGP 200.00 at ATM", "w"),
            transfer.state,
        )
        assertEquals(ParseStatus.PARSED, withdrawal.status)
        assertEquals(TransactionKind.CASH_WITHDRAWAL, withdrawal.state.transactions.last().kind)

        val refund = pipeline.ingest(
            message("VodafoneCash", "Refund of EGP 75.00 from Shop", "r"),
            withdrawal.state,
        )
        assertEquals(ParseStatus.PARSED, refund.status)
        assertEquals(TransactionKind.REFUND, refund.state.transactions.last().kind)

        val reversal = pipeline.ingest(
            message("Vodafone", "Reversed EGP 30.00 at Shop", "v"),
            refund.state,
        )
        assertEquals(ParseStatus.PARSED, reversal.status)
        assertEquals(TransactionKind.REVERSAL, reversal.state.transactions.last().kind)

        val fee = pipeline.ingest(
            message("VodafoneCash", "A service fee of EGP 5.00 was applied", "f"),
            reversal.state,
        )
        assertEquals(ParseStatus.PARSED, fee.status)
        assertEquals(TransactionKind.FEE, fee.state.transactions.last().kind)
        assertTrue(fee.state.reviewQueue().isEmpty())
        assertEquals(6, fee.state.transactions.size)
    }

    @Test
    fun `an unknown sender uses the same service classification and a purchase stays in review`() {
        val open = IngestPipeline(ids = ServiceIds())
        val recharge = open.ingest(message("NEWS", "Recharge successful. You recharged EGP 50", "n1"))
        assertEquals(ParseStatus.IGNORED_NOT_BANK, recharge.status)
        assertNull(recharge.state.messages.single().body)
        assertTrue(recharge.state.reviewQueue().isEmpty())

        val purchase = open.ingest(message("NEWS", "Your card was used for EGP 450 at Talabat", "n2"))
        assertEquals(ParseStatus.UNSUPPORTED, purchase.status)
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
