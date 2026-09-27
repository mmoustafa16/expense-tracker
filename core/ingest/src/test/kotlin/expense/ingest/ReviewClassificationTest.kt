package expense.ingest

import expense.ingest.fixture.SyntheticBankProfile
import expense.parse.BankRegistry
import expense.parse.ParseStatus
import expense.parse.VerifiedBankCatalog
import expense.sms.InboundSms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ReviewClassificationTest {
    private val pipeline = IngestPipeline(ids = CountingIds())

    @Test
    fun `ordinary otp promotional and non transactional notices stay out of review`() {
        val ignored = listOf(
            "See you at dinner",
            "Your OTP is 482193",
            "OTP 482193 to confirm payment of EGP 20",
            "Save EGP 50 this weekend. Use code 20",
            "Your available balance is EGP 1,250.00",
            "Payment due EGP 500",
            "Your statement is ready for account 1234",
        )
        var state = pipeline.ingest(sms("LAB", ignored.first(), 0)).state
        ignored.drop(1).forEachIndexed { index, body ->
            state = pipeline.ingest(sms("LAB", body, index + 1L), state).state
        }
        assertTrue(state.attempts.all { it.status == ParseStatus.IGNORED_NOT_BANK })
        assertTrue(state.messages.all { it.body == null })
        assertTrue(state.reviewQueue().isEmpty())
        assertTrue(state.transactions.isEmpty())
        assertTrue(VerifiedBankCatalog.registry().profiles.isEmpty())
    }

    @Test
    fun `a probable transaction without a verified profile stays in review and is not posted`() {
        val result = pipeline.ingest(sms("OTHER", "Debited EGP 20.00 for Shop", 0))
        assertEquals(ParseStatus.UNSUPPORTED, result.status)
        assertEquals("Debited EGP 20.00 for Shop", result.state.messages.single().body)
        assertEquals(1, result.state.reviewQueue().size)
        assertTrue(result.state.transactions.isEmpty())
        assertTrue(VerifiedBankCatalog.registry().profiles.isEmpty())
    }

    @Test
    fun `a verified template reaches the ledger`() {
        val supplied = IngestPipeline(BankRegistry(listOf(SyntheticBankProfile.create())), CountingIds())
        val result = supplied.ingest(
            sms(
                SyntheticBankProfile.SENDER,
                "TB|purchase|EGP|10.00|Shop|4242|S1|15/01/2026 10:00",
                0,
            ),
        )
        assertEquals(ParseStatus.PARSED, result.status)
        assertEquals(1, result.state.transactions.size)
        assertEquals(false, result.state.transactions.single().manual)
        assertTrue(result.state.reviewQueue().isEmpty())
        assertTrue(VerifiedBankCatalog.registry().profiles.isEmpty())
    }

    private fun sms(sender: String, body: String, offsetSeconds: Long): InboundSms {
        return InboundSms(
            sender = sender,
            body = body,
            providerMessageId = "m-$offsetSeconds",
            receivedAt = Instant.parse("2026-01-15T08:00:00Z").plusSeconds(offsetSeconds),
        )
    }
}

private class CountingIds : IdGenerator {
    private var next = 0

    override fun newId(): String = "id-${next++}"
}
