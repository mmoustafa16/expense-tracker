package expense.ingest

import expense.intelligence.DiscoveryStatus
import expense.intelligence.FinancialSmsIntelligence
import expense.intelligence.InstitutionBootstrap
import expense.intelligence.RegisteredSender
import expense.intelligence.SmsText
import expense.money.Currency
import expense.money.Money
import expense.parse.BankRegistry
import expense.parse.FinancialEventType
import expense.parse.ParseStatus
import expense.parse.VerifiedBankCatalog
import expense.sms.InboundSms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * No parser is written for a new institution.
 *
 * A single message is never enough to make a sender an institution, however
 * bank-shaped it looks. The channel earns the right to post from what this
 * device has already seen it do, and a handset number never earns it.
 */
class SyntheticUnknownBankTest {
    private val sender = "FERRY"
    private val body = "Spent EGP 64.20 at Harbor Cafe"
    private val instrument = "Spent EGP 64.20 at Harbor Cafe using card ****4242"

    @Test
    fun `one bank-shaped message does not verify a new sender`() {
        assertTrue(InstitutionBootstrap.records.none { sender in it.senderIds })
        assertTrue(VerifiedBankCatalog.registry().profiles.isEmpty())
        val bare = FinancialSmsIntelligence.deterministic().assess(SmsText(sender, body))
        assertEquals(DiscoveryStatus.KNOWN, bare.discovery.status)
        assertNull(bare.discovery.verifiedInstitution)
        assertFalse(bare.postable)

        val stated = FinancialSmsIntelligence.deterministic().assess(SmsText(sender, instrument))
        assertEquals(FinancialEventType.CARD_PURCHASE, stated.classification.eventType)
        assertEquals(Money(6420, Currency.EGP), stated.entities.amount)
        assertEquals("Harbor Cafe", stated.entities.merchant)
        assertEquals("4242", stated.entities.accountMask)
        assertNull(stated.discovery.verifiedInstitution)
        assertFalse(stated.postable)
    }

    @Test
    fun `a repeated history of movements earns the channel the right to post`() {
        val pipeline = IngestPipeline(ids = FerryIds())
        var state = pipeline.ingest(sms("ferry-1", instrument)).state
        assertEquals(1, state.reviewQueue().size)
        state = pipeline.ingest(sms("ferry-2", "Cash withdrawal EGP 200.00 at ATM from card ****4242"), state).state
        state = pipeline.ingest(sms("ferry-3", "Transferred EGP 500.00 to Sam from card ****4242"), state).state
        assertTrue(state.transactions.isEmpty())

        val earned = pipeline.ingest(sms("ferry-4", "Spent EGP 12.00 at Harbor Bakery using card ****4242"), state)
        assertEquals(ParseStatus.PARSED, earned.status)
        assertTrue(earned.posted)
        assertEquals("ferry", earned.state.transactions.single().institutionId)

        val handset = pipeline.ingest(
            InboundSms(
                sender = "01005551234",
                body = body,
                providerMessageId = "handset",
                receivedAt = Instant.parse("2026-04-04T08:00:00Z"),
            ),
            earned.state,
        )
        assertEquals(ParseStatus.UNSUPPORTED, handset.status)
        assertEquals("unknown_institution", handset.attempt?.error)
    }

    @Test
    fun `a registered sender posts without a template`() {
        val verified = FinancialSmsIntelligence.deterministic(
            listOf(RegisteredSender("example.ferry-bank", "Ferry Bank", setOf(sender))),
        )
        val posted = IngestPipeline(BankRegistry.EMPTY, FerryIds(), verified).ingest(sms("ferry-posted", body))
        assertEquals(ParseStatus.PARSED, posted.status)
        assertTrue(posted.posted)
        assertNull(posted.attempt?.templateId)
        val transaction = posted.state.transactions.single()
        assertEquals("example.ferry-bank", transaction.institutionId)
        assertEquals(FinancialEventType.CARD_PURCHASE, transaction.eventType)
        assertEquals(Money(6420, Currency.EGP), transaction.amount)
        assertEquals("Harbor Cafe", transaction.merchantRaw)
        assertTrue(posted.state.reviewQueue().isEmpty())
        assertTrue(VerifiedBankCatalog.registry().profiles.isEmpty())
    }

    private fun sms(id: String, text: String = body): InboundSms {
        return InboundSms(
            sender = sender,
            body = text,
            providerMessageId = id,
            receivedAt = Instant.parse("2026-04-04T08:00:00Z"),
        )
    }
}

private class FerryIds : IdGenerator {
    private var next = 0

    override fun newId(): String = "ferry-${next++}"
}
