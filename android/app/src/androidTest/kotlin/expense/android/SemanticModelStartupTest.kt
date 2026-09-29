package expense.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import expense.intelligence.FinancialSmsIntelligence
import expense.intelligence.RoutingOutcome
import expense.intelligence.SemanticTransactionClassifier
import expense.intelligence.SmsText
import expense.money.Currency
import expense.money.Money
import expense.parse.AccountKind
import expense.parse.AmountResolution
import expense.parse.AmountRole
import expense.parse.FinancialEventType
import expense.parse.SpendEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pipeline read by the Android runtime rather than by a desktop JDK.
 *
 * Regex and text handling are not the same on the two: the JDK accepts
 * `Pattern.UNICODE_CHARACTER_CLASS` and Android throws from it, which once
 * killed the process during class initialization before the first screen could
 * open. Arabic wording, digit folding, and the amount role lexicon all run
 * through that machinery, so the messages the device got wrong are read here on
 * the runtime that has to read them.
 */
@RunWith(AndroidJUnit4::class)
class SemanticModelStartupTest {
    private val reader = FinancialSmsIntelligence.deterministic()

    @Test
    fun bundledClassifierInitializesOnAndroid() {
        val body = "عفواً، رصيدك غير كافٍ لتجديد باقة Plus 6000. برجاء شحن 65 جنيه"
        val classification = SemanticTransactionClassifier.bundled().classify(SmsText("Vodafone", body))
        assertEquals("renewal_attempt", classification.semantics?.intent)
        assertEquals(FinancialEventType.PAYMENT_DUE, classification.eventType)
        assertFalse(classification.eventType.canMoveMoney())
    }

    @Test
    fun cardLastFourSurvivesEverySeparatorOnAndroid() {
        val wordings = listOf(
            "Your credit card ****4229 was charged EGP 41.76 at Uber",
            "Your credit card#4229 was charged EGP 41.76 at Uber",
            "Your credit card ending with#4229 was charged EGP 41.76 at Uber",
            "تم خصم 41.76 جنيه من بطاقتكم الائتمانية المنتهية ب 4229",
        )
        wordings.forEach { body ->
            val decision = reader.assess(SmsText("CIB", body))
            assertEquals(body, "4229", decision.entities.accountMask)
            assertEquals(body, AccountKind.CREDIT_CARD, decision.entities.instrument?.kind)
        }
    }

    @Test
    fun arabicAmountRolesAreSeparatedOnAndroid() {
        val body = "تم سحب 3000.00 جنية من محفظتك. رصيد حسابك الحالي 28.15 جنيه. " +
            "يمكنك سحب حتى 5000 جنيه شهرياً"
        val decision = reader.assess(SmsText("VodafoneCash", body))
        assertEquals(AmountResolution.RESOLVED, decision.entities.resolution)
        assertEquals(Money(300000, Currency.EGP), decision.entities.amount)
        assertEquals(Money(2815, Currency.EGP), decision.entities.balance)
        assertEquals(
            listOf(
                AmountRole.TRANSACTION_AMOUNT,
                AmountRole.REMAINING_BALANCE,
                AmountRole.PROMOTIONAL_AMOUNT,
            ),
            decision.entities.amounts.map { it.role },
        )
    }

    @Test
    fun creditCardSettlementIsNotSpendingOnAndroid() {
        val body = "تم سداد مبلغ 505.96 جم فى بطاقتكم الائتمانية المنتهية ب 4229 بتاريخ 26-09-29"
        val decision = reader.assess(SmsText("CIB", body))
        assertEquals(FinancialEventType.CREDIT_CARD_PAYMENT, decision.event.eventType)
        assertEquals(SpendEffect.LIABILITY_SETTLEMENT, decision.event.spendEffect)
        assertFalse(decision.event.spendEffect.countsTowardSpend())
        assertEquals(Money(50596, Currency.EGP), decision.entities.amount)
    }

    @Test
    fun serviceMessagesAreNotReviewedOnAndroid() {
        val bodies = listOf(
            "نشكر زيارتكم لمستشفى السعودي الألماني. نرجو تقييم خدمتنا من 1 إلى 5 عبر الرابط",
            "Your order is out for delivery and will arrive today",
        )
        bodies.forEach { body ->
            val decision = reader.assess(SmsText("SGH", body))
            assertEquals(body, RoutingOutcome.IGNORE, decision.routing.outcome)
            assertNull(body, decision.routing.holdReason)
            assertFalse(body, decision.postable)
        }
    }

    @Test
    fun aCompletedMovementWithNoAmountReachesReviewOnAndroid() {
        val decision = reader.assess(SmsText("CIB", "Your credit card ****4229 was charged at Talabat"))
        assertTrue(decision.event.moneyMovement)
        assertNull(decision.entities.amount)
        assertEquals(RoutingOutcome.REVIEW, decision.routing.outcome)
        assertEquals("amount_missing", decision.routing.holdReason)
    }
}
