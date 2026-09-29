package expense.intelligence

import expense.parse.AccountKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Reasons a financial SMS stays in Review after the sender channel is known.
 * The fixtures are structural. They are not a bank parser.
 */
class CibReviewHoldTest {
    private val intelligence = FinancialSmsIntelligence.deterministic(
        senderEvidence = establishedChannel("CIB"),
    )
    private val unproven = FinancialSmsIntelligence.deterministic()
    private val card =
        "Your credit card#0019 was charged for EGP 31.89 at Talabat on 30/03/24 at 20:19. Available limit is 137968.11."
    private val transfer =
        "Your account ending with ******9438 is debited with amount EGP 31.89DR on 31 MAR 2024 with transfer to another account."

    @Test
    fun `a verified channel posts a completed card charge and account transfer with their dates`() {
        val charged = intelligence.assess(SmsText("CIB", card))
        assertTrue(charged.postable)
        assertNull(charged.reviewHold())
        assertEquals("cib", charged.discovery.verifiedInstitution?.institutionId)
        assertEquals("Talabat", charged.entities.merchant)
        assertEquals("0019", charged.entities.accountMask)
        assertEquals(LocalDateTime.of(2024, 3, 30, 20, 19), charged.entities.occurredAt)

        val moved = intelligence.assess(SmsText("CIB", transfer))
        assertTrue(moved.postable)
        assertNull(moved.reviewHold())
        assertEquals("9438", moved.entities.accountMask)
        assertEquals(LocalDateTime.of(2024, 3, 31, 0, 0), moved.entities.occurredAt)
    }

    @Test
    fun `the same channel cannot post before its history has earned it`() {
        val charged = unproven.assess(SmsText("CIB", card))
        assertFalse(charged.postable)
        assertEquals("unverified_institution", charged.reviewHold())
        assertEquals("0019", charged.entities.accountMask)
    }

    @Test
    fun `a clear charge without a merchant still posts and incomplete messages stay in review`() {
        val plain = intelligence.assess(SmsText("CIB", "Your card was charged EGP 40.00"))
        assertTrue(plain.postable)
        assertNull(plain.entities.merchant)
        assertNull(plain.entities.accountMask)

        val masked = intelligence.assess(
            SmsText("CIB", "Your credit card ****4229 was charged EGP 41.76 at Uber on 28-09-2026 at 19:58."),
        )
        assertTrue(masked.postable)
        assertEquals("4229", masked.entities.accountMask)
        assertEquals(
            AccountKind.CREDIT_CARD,
            paymentInstrument("Your credit card ****4229 was charged EGP 41.76 at Uber on 28-09-2026 at 19:58.")?.kind,
        )
        assertEquals("Uber", masked.entities.merchant)
        assertEquals(LocalDateTime.of(2026, 9, 28, 19, 58), masked.entities.occurredAt)

        val arabic = intelligence.assess(SmsText("CIB", "تم استخدام بطاقتك لشراء مبلغ 65 جنيه لدى المتجر"))
        assertTrue(arabic.postable)
        assertEquals("المتجر", arabic.entities.merchant)

        val twoAmounts = intelligence.assess(SmsText("CIB", "You paid EGP 20.00 and EGP 5.00 at Shop"))
        assertEquals("ambiguous_amount", twoAmounts.reviewHold())
        assertFalse(twoAmounts.postable)

        val noCurrency = intelligence.assess(SmsText("CIB", "Your account was debited with 31.89"))
        assertEquals("amount_missing", noCurrency.reviewHold())

        val unclear = intelligence.assess(SmsText("CIB", "Refunded or reversed EGP 40.00 from Shop"))
        assertEquals("ambiguous_meaning", unclear.reviewHold())

        val handset = intelligence.assess(SmsText("01005551234", "Charged EGP 10.00 at Shop"))
        assertEquals("unknown_institution", handset.reviewHold())

        val balance = intelligence.assess(SmsText("CIB", "Your available balance is EGP 1,250.00"))
        assertFalse(balance.classification.eventType.canMoveMoney())
        assertNull(balance.reviewHold())
        assertFalse(balance.postable)
    }
}
