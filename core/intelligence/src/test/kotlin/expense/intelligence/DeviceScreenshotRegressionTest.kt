package expense.intelligence

import expense.money.Currency
import expense.money.Money
import expense.parse.AccountKind
import expense.parse.AmountResolution
import expense.parse.AmountRole
import expense.parse.FinancialEventType
import expense.parse.SpendEffect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The five wordings the device screenshots showed, read by the pipeline that
 * replaced the one that got them wrong.
 *
 * Each of these failed for a structural reason rather than a missing phrase: an
 * identifier was matched by an ordered template instead of scored as a
 * candidate, a number's meaning was guessed from the gap before it instead of
 * from the clause containing it, and a label the model picked was allowed to
 * assert that money moved. None of the expectations below name an institution,
 * a sender, or a phrase peculiar to one bank.
 */
class DeviceScreenshotRegressionTest {
    private val reader = FinancialSmsIntelligence.deterministic()
    private val proven = FinancialSmsIntelligence.deterministic(
        senderEvidence = establishedChannel("CIB", "VodafoneCash"),
    )

    @Test
    fun `a last four separated by a hash after the preposition is still the card`() {
        val body = "Your credit card ending with#4229 was charged for EGP 94.91 at Uber on " +
            "28/09/26 at 20:40. Card available limit is EGP 79809.38."
        val decision = proven.assess(SmsText("CIB", body))
        assertEquals("4229", decision.entities.accountMask)
        assertEquals(AccountKind.CREDIT_CARD, decision.entities.instrument?.kind)
        assertEquals(FinancialEventType.CARD_PURCHASE, decision.event.eventType)
        assertEquals(Money(9491, Currency.EGP), decision.entities.amount)
        assertEquals("Uber", decision.entities.merchant)
        assertTrue(decision.postable)
    }

    @Test
    fun `the same last four is found however the message separates it`() {
        val wordings = listOf(
            "Your credit card ****4229 was charged EGP 41.76 at Uber",
            "Your credit card#4229 was charged EGP 41.76 at Uber",
            "Your credit card ending with ****4229 was charged EGP 41.76 at Uber",
            "Your credit card ending with#4229 was charged EGP 41.76 at Uber",
            "Your credit card no. 4229 was charged EGP 41.76 at Uber",
            "تم خصم 41.76 جنيه من بطاقتكم الائتمانية المنتهية ب 4229",
        )
        wordings.forEach { body ->
            val instrument = paymentInstrument(body)
            assertEquals("4229", instrument?.mask, body)
            assertEquals(AccountKind.CREDIT_CARD, instrument?.kind, body)
        }
        val unspecified = paymentInstrument("تم خصم 41.76 جنيه من البطاقة رقم ****4229")
        assertEquals("4229", unspecified?.mask)
        assertEquals(AccountKind.CARD, unspecified?.kind)
    }

    @Test
    fun `paying a credit card settles a liability and is not spending`() {
        val body = "تم سداد مبلغ 505.96 جم فى بطاقتكم الائتمانية المنتهية ب 4229 بتاريخ 26-09-29"
        val decision = proven.assess(SmsText("CIB", body))
        assertEquals(FinancialEventType.CREDIT_CARD_PAYMENT, decision.event.eventType)
        assertEquals(SpendEffect.LIABILITY_SETTLEMENT, decision.event.spendEffect)
        assertFalse(decision.event.spendEffect.countsTowardSpend())
        assertTrue(decision.event.moneyMovement)
        assertEquals(Money(50596, Currency.EGP), decision.entities.amount)
        assertEquals("4229", decision.entities.accountMask)
        assertNull(decision.reviewHold())
        assertTrue(decision.postable)
    }

    /**
     * The roles are the point. Whether the label is a withdrawal or a transfer
     * is a question for the classifier and a known limit of the bundled one; the
     * three numbers still have to be told apart before any label is chosen.
     */
    @Test
    fun `a withdrawal a remaining balance and an advertised ceiling each keep their own role`() {
        val body = "تم سحب 3000.00 جنية من محفظتك. رصيد حسابك الحالي 28.15 جنيه. " +
            "يمكنك سحب حتى 5000 جنيه شهرياً"
        val decision = proven.assess(SmsText("VodafoneCash", body))
        assertTrue(decision.event.moneyMovement)
        assertEquals(AmountResolution.RESOLVED, decision.entities.resolution)
        assertEquals(Money(300000, Currency.EGP), decision.entities.amount)
        assertEquals(Money(2815, Currency.EGP), decision.entities.balance)
        assertEquals(
            listOf(AmountRole.TRANSACTION_AMOUNT, AmountRole.REMAINING_BALANCE, AmountRole.PROMOTIONAL_AMOUNT),
            decision.entities.amounts.map { it.role },
        )
        assertNull(decision.reviewHold())
        assertTrue(decision.postable)
    }

    @Test
    fun `a survey from a service that mentions no money is not financial and not reviewed`() {
        val body = "نشكر زيارتكم لمستشفى السعودي الألماني. نرجو تقييم خدمتنا من 1 إلى 5 عبر الرابط"
        val decision = reader.assess(SmsText("SGH", body))
        assertEquals(FinancialEventType.NOT_FINANCIAL, decision.event.eventType)
        assertFalse(decision.event.eventType.isFinancialEvent())
        assertFalse(decision.event.moneyMovement)
        assertEquals(RoutingOutcome.IGNORE, decision.routing.outcome)
        assertNull(decision.reviewHold())
        assertFalse(decision.postable)
    }

    @Test
    fun `an advance notice of a future deduction is a due reminder rather than a transaction`() {
        val body = "Dear customer, stamp duty fees of 220 EGP will be deducted from your account on 08/01/2026."
        val decision = reader.assess(SmsText("SAIB Bank", body))
        assertFalse(decision.event.completed)
        assertFalse(decision.event.moneyMovement)
        assertEquals(RoutingOutcome.IGNORE, decision.routing.outcome)
        assertNull(decision.reviewHold())
        assertFalse(decision.postable)
    }

    @Test
    fun `a completed movement whose amount the message never states is held for review`() {
        val decision = proven.assess(SmsText("CIB", "Your credit card ****4229 was charged at Talabat"))
        assertEquals(FinancialEventType.CARD_PURCHASE, decision.event.eventType)
        assertTrue(decision.event.moneyMovement)
        assertNull(decision.entities.amount)
        assertEquals(AmountResolution.MISSING, decision.entities.resolution)
        assertEquals(RoutingOutcome.REVIEW, decision.routing.outcome)
        assertEquals("amount_missing", decision.reviewHold())
    }

    @Test
    fun `a missing amount alone never sends a message to review`() {
        val silent = listOf(
            "نشكر زيارتكم لمستشفى السعودي الألماني. نرجو تقييم خدمتنا من 1 إلى 5 عبر الرابط",
            "Your order is out for delivery and will arrive today",
            "Your appointment is confirmed for tomorrow at 5",
            "Save big this weekend with code SUMMER",
            "Your OTP is 482193",
        )
        silent.forEach { body ->
            val decision = reader.assess(SmsText("SERVICE", body))
            assertNull(decision.entities.amount, body)
            assertEquals(RoutingOutcome.IGNORE, decision.routing.outcome, body)
            assertNull(decision.reviewHold(), body)
        }
    }
}
