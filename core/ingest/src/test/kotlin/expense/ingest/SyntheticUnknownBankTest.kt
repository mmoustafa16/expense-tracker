package expense.ingest

import expense.intelligence.DiscoveryStatus
import expense.intelligence.FinancialSmsIntelligence
import expense.intelligence.InstitutionBootstrap
import expense.intelligence.RegisteredSender
import expense.intelligence.SmsText
import expense.intelligence.TransactionClass
import expense.money.Currency
import expense.money.Money
import expense.parse.BankRegistry
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

/**
 * A bank that is not on the current device. No parser is written for it.
 * The shared pipeline understands the message and holds it until the sender
 * is added as a verified record.
 */
class SyntheticUnknownBankTest {
    private val sender = "FERRY"
    private val body = "Spent EGP 64.20 at Harbor Cafe"

    @Test
    fun `a synthetic unknown bank is understood and stays in review until verified`() {
        assertTrue(InstitutionBootstrap.records.isEmpty())
        assertTrue(VerifiedBankCatalog.registry().profiles.isEmpty())
        val understood = FinancialSmsIntelligence.deterministic().assess(SmsText(sender, body))
        assertEquals(DiscoveryStatus.UNKNOWN, understood.discovery.status)
        assertEquals(TransactionClass.CARD_PURCHASE, understood.classification.type)
        assertEquals(Money(6420, Currency.EGP), understood.entities.amount)
        assertEquals(Currency.EGP, understood.entities.currency)
        assertEquals("Harbor Cafe", understood.entities.merchant)
        assertFalse(understood.postable)

        val pipeline = IngestPipeline(ids = FerryIds())
        val held = pipeline.ingest(sms("ferry-held"))
        assertEquals(ParseStatus.UNSUPPORTED, held.status)
        assertFalse(held.posted)
        assertNull(held.attempt?.templateId)
        assertTrue(held.state.transactions.isEmpty())
        assertEquals(1, held.state.reviewQueue().size)
        assertEquals(body, held.state.messages.single().body)

        val verified = FinancialSmsIntelligence.deterministic(
            listOf(RegisteredSender("example.ferry-bank", "Ferry Bank", setOf(sender))),
        )
        val posted = IngestPipeline(BankRegistry.EMPTY, FerryIds(), verified).ingest(sms("ferry-posted"))
        assertEquals(ParseStatus.PARSED, posted.status)
        assertTrue(posted.posted)
        assertNull(posted.attempt?.templateId)
        assertFalse(posted.matchedProfile)
        val transaction = posted.state.transactions.single()
        assertEquals("example.ferry-bank", transaction.institutionId)
        assertEquals(TransactionKind.PURCHASE, transaction.kind)
        assertEquals(Money(6420, Currency.EGP), transaction.amount)
        assertEquals("Harbor Cafe", transaction.merchantRaw)
        assertTrue(posted.state.reviewQueue().isEmpty())
        assertTrue(InstitutionBootstrap.records.isEmpty())
        assertTrue(VerifiedBankCatalog.registry().profiles.isEmpty())
    }

    private fun sms(id: String): InboundSms {
        return InboundSms(
            sender = sender,
            body = body,
            providerMessageId = id,
            receivedAt = Instant.parse("2026-04-04T08:00:00Z"),
        )
    }
}

private class FerryIds : IdGenerator {
    private var next = 0

    override fun newId(): String = "ferry-${next++}"
}
