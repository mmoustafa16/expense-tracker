package expense.ingest

import expense.intelligence.FinancialSmsIntelligence
import expense.intelligence.MoneyDirection
import expense.intelligence.SmsText
import expense.parse.ParseStatus
import expense.sms.InboundSms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Held-out bank-advice wording. An unknown sender cannot post, so a completed
 * transfer or purchase stays in Review. A balance notice is not a transaction.
 */
class AccountTransferReviewTest {
    private val cibTransfer =
        "Your account ending with ******9438 is debited with amount EGP 31.89DR on 31 MAR 2024 with transfer to another account."

    @Test
    fun `an unknown sender account transfer stays in review`() {
        val bodies = listOf(
            cibTransfer,
            "The account was debited because a transfer to another account completed on 31 March.",
            "Funds left the account through a completed transfer. The debit has posted.",
            "Your account was debited EGP 75.00 for a transfer to another account. Available limit remains EGP 4,000.00.",
        )
        val pipeline = IngestPipeline(ids = TransferIds())
        bodies.forEachIndexed { index, body ->
            val result = pipeline.ingest(message("CIB", body, "t$index"))
            assertEquals(ParseStatus.UNSUPPORTED, result.status, body)
            assertEquals(1, result.state.reviewQueue().size, body)
            assertEquals(body, result.state.messages.single().body, body)
            assertTrue(result.state.transactions.isEmpty(), body)
            val decision = FinancialSmsIntelligence.deterministic().assess(SmsText("CIB", body))
            assertEquals("transfer_out", decision.classification.semantics?.intent, body)
            assertTrue(decision.classification.semantics!!.transactionCompleted, body)
            assertTrue(decision.classification.semantics!!.moneyMovement, body)
            assertEquals(MoneyDirection.DEBIT, decision.classification.semantics!!.direction, body)
            assertTrue(decision.classification.confidence >= 80, body)
        }
    }

    @Test
    fun `an account credit from a transfer stays in review for an unknown sender`() {
        val body = "Your account was credited with amount EGP 120.00CR on 02 APR 2024 from a transfer by another account."
        val result = IngestPipeline(ids = TransferIds()).ingest(message("CIB", body, "credit"))
        assertEquals(ParseStatus.UNSUPPORTED, result.status)
        assertEquals(1, result.state.reviewQueue().size)
        assertTrue(result.state.transactions.isEmpty())
        val decision = FinancialSmsIntelligence.deterministic().assess(SmsText("CIB", body))
        assertEquals("transfer_in", decision.classification.semantics?.intent)
        assertEquals(MoneyDirection.CREDIT, decision.classification.semantics!!.direction)
    }

    @Test
    fun `an account purchase that also quotes available balance stays in review`() {
        val body = "Your account ending with ****2219 is debited with amount EGP 54.00DR on 04 APR 2024 for a purchase at the market."
        val result = IngestPipeline(ids = TransferIds()).ingest(message("CIB", body, "purchase"))
        assertEquals(ParseStatus.UNSUPPORTED, result.status)
        assertEquals(1, result.state.reviewQueue().size)
        assertTrue(result.state.transactions.isEmpty())
        val decision = FinancialSmsIntelligence.deterministic().assess(SmsText("CIB", body))
        assertEquals("card_purchase", decision.classification.semantics?.intent)
        assertEquals(MoneyDirection.DEBIT, decision.classification.semantics!!.direction)
    }

    @Test
    fun `a balance notice that mentions no transfer is ignored`() {
        val body = "Your account ending with ****1008 has available balance EGP 500.00. No transfer or debit took place."
        val result = IngestPipeline(ids = TransferIds()).ingest(message("CIB", body, "balance"))
        assertEquals(ParseStatus.IGNORED_NOT_BANK, result.status)
        assertTrue(result.state.reviewQueue().isEmpty())
        assertTrue(result.state.transactions.isEmpty())
        assertEquals(null, result.state.messages.single().body)
    }

    private fun message(sender: String, body: String, id: String): InboundSms {
        return InboundSms(
            sender = sender,
            body = body,
            providerMessageId = id,
            receivedAt = Instant.parse("2024-03-31T12:00:00Z"),
        )
    }
}

private class TransferIds : IdGenerator {
    private var next = 0

    override fun newId(): String = "transfer-${next++}"
}
