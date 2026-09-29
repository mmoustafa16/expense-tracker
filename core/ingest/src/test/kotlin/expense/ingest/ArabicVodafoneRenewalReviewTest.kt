package expense.ingest

import expense.intelligence.FinancialSmsIntelligence
import expense.intelligence.RegisteredSender
import expense.intelligence.SmsText
import expense.parse.FinancialEventType
import expense.parse.ParseStatus
import expense.sms.InboundSms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Real-device regression: an Arabic Vodafone package-renewal failure is a
 * service event. It must not be kept in Review for a known wallet sender or
 * for an unknown sender. A completed Arabic card purchase from an unknown
 * sender still stays in Review.
 */
class ArabicVodafoneRenewalReviewTest {
    private val deviceRenewal = "عفواً، رصيدك غير كافٍ لتجديد باقة Plus 6000. برجاء شحن 65 جنيه"
    private val arabicPurchase = "تم استخدام بطاقتك لشراء مبلغ 65 جنيه لدى المتجر"
    private val wallet = RegisteredSender(
        "example.vodafone-cash",
        "Vodafone Cash",
        setOf("Vodafone", "VodafoneCash"),
    )

    @Test
    fun `arabic renewal attempts stay out of review for known and unknown senders`() {
        val attempts = listOf(
            deviceRenewal,
            "فشل تجديد الباقة لعدم كفاية الرصيد. يرجى إعادة الشحن",
            "Plus 6000 لم يتم تجديدها لأن الرصيد غير كافٍ، اشحن 65 جنيه",
            "The bundle could not renew لأن الرصيد أقل من 65 جنيه",
        )
        val knownPipeline = IngestPipeline(
            ids = RenewalIds(),
            intelligence = FinancialSmsIntelligence.deterministic(listOf(wallet)),
        )
        val unknownPipeline = IngestPipeline(ids = RenewalIds())
        attempts.forEachIndexed { index, body ->
            val known = knownPipeline.ingest(message("Vodafone", body, "k$index"))
            val unknown = unknownPipeline.ingest(message("VF-EG", body, "u$index"))
            assertEquals(ParseStatus.IGNORED_NOT_BANK, known.status, body)
            assertEquals(ParseStatus.IGNORED_NOT_BANK, unknown.status, body)
            assertNull(known.state.messages.single().body, body)
            assertNull(unknown.state.messages.single().body, body)
            assertTrue(known.state.reviewQueue().isEmpty(), body)
            assertTrue(unknown.state.reviewQueue().isEmpty(), body)
            assertTrue(known.state.transactions.isEmpty(), body)
            assertTrue(unknown.state.transactions.isEmpty(), body)

            val decision = FinancialSmsIntelligence.deterministic().assess(SmsText("Vodafone", body))
            assertEquals("renewal_attempt", decision.classification.semantics?.intent, body)
            assertEquals(FinancialEventType.PAYMENT_DUE, decision.classification.eventType, body)
            assertFalse(decision.classification.eventType.canMoveMoney(), body)
            assertFalse(decision.event.moneyMovement, body)
        }
    }

    @Test
    fun `an arabic completed card purchase without an instrument stays in review`() {
        val open = IngestPipeline(ids = RenewalIds())
        val purchase = open.ingest(message("VF-EG", arabicPurchase, "purchase"))
        assertEquals(ParseStatus.UNSUPPORTED, purchase.status)
        assertEquals("unverified_institution", purchase.attempt?.error)
        assertFalse(purchase.posted)
        assertTrue(purchase.state.transactions.isEmpty())
        val decision = FinancialSmsIntelligence.deterministic().assess(SmsText("VF-EG", arabicPurchase))
        assertEquals("card_purchase", decision.classification.semantics?.intent)
        assertEquals(FinancialEventType.CARD_PURCHASE, decision.classification.eventType)
        assertTrue(decision.event.moneyMovement)
        val handset = open.ingest(message("01005551234", arabicPurchase, "handset"))
        assertEquals(ParseStatus.UNSUPPORTED, handset.status)
        assertEquals("unknown_institution", handset.attempt?.error)
        assertTrue(handset.state.transactions.isEmpty())
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

private class RenewalIds : IdGenerator {
    private var next = 0

    override fun newId(): String = "renewal-${next++}"
}
