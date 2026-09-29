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
 * No parser is written for a new institution.
 * An alphanumeric sender posts when the SMS states a card or account, and a
 * later message from that same sender can post without repeating the digits.
 * A handset number is not an institution.
 */
class SyntheticUnknownBankTest {
    private val sender = "FERRY"
    private val body = "Spent EGP 64.20 at Harbor Cafe"
    private val instrument = "Spent EGP 64.20 at Harbor Cafe using card ****4242"

    @Test
    fun `an instrument verifies a new sender and a handset stays in review`() {
        assertTrue(InstitutionBootstrap.records.none { sender in it.senderIds })
        assertTrue(VerifiedBankCatalog.registry().profiles.isEmpty())
        val bare = FinancialSmsIntelligence.deterministic().assess(SmsText(sender, body))
        assertEquals(DiscoveryStatus.KNOWN, bare.discovery.status)
        assertNull(bare.discovery.verifiedInstitution)
        assertFalse(bare.postable)

        val understood = FinancialSmsIntelligence.deterministic().assess(SmsText(sender, instrument))
        assertEquals("ferry", understood.discovery.verifiedInstitution?.institutionId)
        assertEquals("FERRY", understood.discovery.verifiedInstitution?.displayName)
        assertEquals(TransactionClass.CARD_PURCHASE, understood.classification.type)
        assertEquals(Money(6420, Currency.EGP), understood.entities.amount)
        assertEquals("Harbor Cafe", understood.entities.merchant)
        assertEquals("4242", understood.entities.accountMask)
        assertTrue(understood.postable)

        val pipeline = IngestPipeline(ids = FerryIds())
        val postedChannel = pipeline.ingest(sms("ferry-held", instrument))
        assertEquals(ParseStatus.PARSED, postedChannel.status)
        assertTrue(postedChannel.posted)
        assertEquals("ferry", postedChannel.state.transactions.single().institutionId)
        val followed = pipeline.ingest(sms("ferry-next", body), postedChannel.state)
        assertEquals(ParseStatus.PARSED, followed.status)
        assertEquals(2, followed.state.transactions.size)
        assertNull(followed.state.transactions.last().accountId)

        val handset = pipeline.ingest(
            InboundSms(sender = "01005551234", body = body, providerMessageId = "handset", receivedAt = Instant.parse("2026-04-04T08:00:00Z")),
            postedChannel.state,
        )
        assertEquals(ParseStatus.UNSUPPORTED, handset.status)
        assertEquals("unknown_institution", handset.attempt?.error)
        assertEquals(1, handset.state.reviewQueue().size)

        val verified = FinancialSmsIntelligence.deterministic(
            listOf(RegisteredSender("example.ferry-bank", "Ferry Bank", setOf(sender))),
        )
        val posted = IngestPipeline(BankRegistry.EMPTY, FerryIds(), verified).ingest(sms("ferry-posted", body))
        assertEquals(ParseStatus.PARSED, posted.status)
        assertTrue(posted.posted)
        assertNull(posted.attempt?.templateId)
        val transaction = posted.state.transactions.single()
        assertEquals("example.ferry-bank", transaction.institutionId)
        assertEquals(TransactionKind.PURCHASE, transaction.kind)
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
