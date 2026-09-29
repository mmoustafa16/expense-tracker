package expense.ingest

import expense.intelligence.DiscoveryStatus
import expense.intelligence.FinancialSmsIntelligence
import expense.intelligence.InstitutionEvidencePolicy
import expense.intelligence.SmsText
import expense.parse.ParseStatus
import expense.sms.InboundSms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Which senders may authorize a ledger row, decided from what this device has
 * watched them do.
 *
 * An alphanumeric address is not an institution. Hospitals, restaurants,
 * delivery services and telecom marketing all send from one, and any of their
 * messages can read as financial for a moment. What separates a notifying
 * institution from them is the shape of a whole history: repeated completed
 * movements, more than one form of them, and instrument identifiers with a
 * consistent mask. A sender below that bar is not rejected; its completed
 * movements wait in Review, where the account holder can act on them.
 */
class InstitutionDiscoveryRegressionTest {
    private val receivedAt = Instant.parse("2026-09-29T17:58:00Z")

    @Test
    fun `a clear transaction from an unknown institution waits in review with its body`() {
        val pipeline = IngestPipeline(ids = DiscoveryIds())
        val body = "Spent EGP 64.20 at Harbor Cafe using card ****4242"
        val result = pipeline.ingest(message("01005551234", body, "handset"))
        assertEquals(ParseStatus.UNSUPPORTED, result.status)
        assertEquals("unknown_institution", result.attempt?.error)
        assertEquals(1, result.state.reviewQueue().size)
        assertEquals(body, result.state.messages.single().body)
        assertTrue(result.state.transactions.isEmpty())
        assertTrue(result.state.accounts.isEmpty())
        assertTrue(result.financial)
    }

    @Test
    fun `a handset number never earns verification however much it sends`() {
        val pipeline = IngestPipeline(ids = DiscoveryIds())
        var state = pipeline.ingest(message("01005551234", "Spent EGP 64.20 at Harbor Cafe using card ****4242", "h0")).state
        repeat(8) { index ->
            val body = "Spent EGP ${index + 10}.00 at Harbor Cafe using card ****4242"
            state = pipeline.ingest(message("01005551234", body, "h${index + 1}"), state).state
        }
        assertTrue(state.transactions.isEmpty())
        assertEquals(9, state.reviewQueue().size)
        val record = pipeline.changedSenderEvidence().single { it.sender == "01005551234" }
        assertEquals(9, record.movementEvents)
        assertFalse(InstitutionEvidencePolicy.DEFAULT.verifies(record))
    }

    @Test
    fun `service channels repeating their own business never become institutions`() {
        val channels = mapOf(
            "SGH" to "نشكر زيارتكم لمستشفى السعودي الألماني. نرجو تقييم خدمتنا من 1 إلى 5 عبر الرابط",
            "TALABAT" to "Your order is on the way and will arrive in 20 minutes",
            "CAFEROMA" to "Table for two confirmed for tonight at 8",
            "PROMO-EG" to "Save big this weekend with code SUMMER",
        )
        channels.forEach { (sender, body) ->
            val pipeline = IngestPipeline(ids = DiscoveryIds())
            var state = pipeline.ingest(message(sender, body, "$sender-0")).state
            repeat(8) { index ->
                state = pipeline.ingest(message(sender, "$body ($index)", "$sender-${index + 1}"), state).state
            }
            assertTrue(state.transactions.isEmpty(), sender)
            assertTrue(state.reviewQueue().isEmpty(), sender)
            assertTrue(state.messages.all { it.body == null }, sender)
            val decision = FinancialSmsIntelligence.deterministic().assess(SmsText(sender, body))
            assertNull(decision.discovery.verifiedInstitution, sender)
            assertFalse(decision.postable, sender)
        }
    }

    @Test
    fun `one financial-looking message from a service channel does not verify it`() {
        val pipeline = IngestPipeline(ids = DiscoveryIds())
        var state = pipeline.ingest(message("SGH", "Your appointment is confirmed for tomorrow", "s0")).state
        state = pipeline.ingest(message("SGH", "Your appointment fee of EGP 350.00 was paid at reception", "s1"), state).state
        assertTrue(state.transactions.isEmpty())
        val decision = FinancialSmsIntelligence.deterministic().assess(
            SmsText("SGH", "Your appointment fee of EGP 350.00 was paid at reception"),
        )
        assertEquals(DiscoveryStatus.KNOWN, decision.discovery.status)
        assertNull(decision.discovery.verifiedInstitution)
        assertEquals("unverified_institution", decision.routing.holdReason)
    }

    @Test
    fun `verification is earned by a history of movements in more than one form`() {
        val pipeline = IngestPipeline(ids = DiscoveryIds())
        val history = listOf(
            "Spent EGP 64.20 at Harbor Cafe using card ****4242",
            "Cash withdrawal EGP 200.00 at ATM from card ****4242",
            "Transferred EGP 500.00 to Sam from card ****4242",
        )
        var state = pipeline.ingest(message(CHANNEL, history[0], "c0")).state
        history.drop(1).forEachIndexed { index, body ->
            state = pipeline.ingest(message(CHANNEL, body, "c${index + 1}"), state).state
        }
        assertTrue(state.transactions.isEmpty())
        assertEquals(3, state.reviewQueue().size)

        val earned = pipeline.ingest(message(CHANNEL, "Spent EGP 12.00 at Harbor Bakery using card ****4242", "c9"), state)
        assertEquals(ParseStatus.PARSED, earned.status)
        assertTrue(earned.posted)
        assertEquals("harbourbank", earned.state.transactions.single().institutionId)
    }

    private fun message(sender: String, body: String, id: String): InboundSms {
        return InboundSms(
            sender = sender,
            body = body,
            providerMessageId = id,
            receivedAt = receivedAt,
        )
    }

    private companion object {
        const val CHANNEL = "HarbourBank"
    }
}

private class DiscoveryIds : IdGenerator {
    private var next = 0

    override fun newId(): String = "discovery-${next++}"
}
