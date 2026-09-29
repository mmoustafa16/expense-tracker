package expense.ingest

import expense.ingest.fixture.SyntheticBankProfile
import expense.parse.BankRegistry
import expense.parse.ParseStatus
import expense.parse.VerifiedBankCatalog
import expense.sms.InboundSms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class VerifiedBankCatalogTest {
    @Test
    fun `the production catalog is empty and an unmatched financial sms is not posted`() {
        assertTrue(VerifiedBankCatalog.registry().profiles.isEmpty())
        val result = IngestPipeline(VerifiedBankCatalog.registry()).ingest(
            InboundSms(
                sender = "01005551234",
                body = "Debited EGP 20.00 for Shop",
                providerMessageId = "1",
                receivedAt = Instant.parse("2026-01-15T08:00:00Z"),
            ),
        )
        assertEquals(ParseStatus.UNSUPPORTED, result.status)
        assertTrue(result.financial)
        assertFalse(result.matchedProfile)
        assertFalse(result.posted)
        assertTrue(result.state.transactions.isEmpty())
        assertEquals(1, result.state.reviewQueue().size)
        val tally = IngestTally().add(result)
        assertEquals(1, tally.scanned)
        assertEquals(1, tally.financial)
        assertEquals(0, tally.matchedProfile)
        assertEquals(1, tally.unsupported)
        assertEquals(0, tally.parsed)
        assertEquals(0, tally.posted)
        assertTrue(VerifiedBankCatalog.registry().profiles.isEmpty())
    }

    @Test
    fun `a transaction is posted only when the pipeline is given a verified profile`() {
        val supplied = IngestPipeline(BankRegistry(listOf(SyntheticBankProfile.create())))
        val result = supplied.ingest(
            InboundSms(
                sender = SyntheticBankProfile.SENDER,
                body = "TB|purchase|EGP|10.00|Shop|4242|S1|15/01/2026 10:00",
                providerMessageId = "1",
                receivedAt = Instant.parse("2026-01-15T08:00:00Z"),
            ),
        )
        assertEquals(ParseStatus.PARSED, result.status)
        assertTrue(result.matchedProfile)
        assertTrue(result.posted)
        assertEquals(1, result.state.transactions.size)
        assertTrue(VerifiedBankCatalog.registry().profiles.isEmpty())
        assertTrue(BankRegistry.EMPTY.profiles.isEmpty())
    }
}
