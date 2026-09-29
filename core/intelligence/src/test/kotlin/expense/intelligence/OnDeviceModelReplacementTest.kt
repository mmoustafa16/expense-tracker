package expense.intelligence

import expense.money.Currency
import expense.money.Money
import expense.parse.AmountResolution
import expense.parse.AmountRole
import expense.parse.FinancialEventType
import expense.parse.RoledAmount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stand-in for a future on-device model. It is not a bank parser.
 * The shared validator and ledger rule still decide posting.
 */
class OnDeviceModelReplacementTest {
    private val body = "Spent EGP 64.20 at Harbor Cafe"
    private val classifier = TransactionClassifier {
        Classification(FinancialEventType.CARD_PURCHASE, confidence = 93, ambiguous = false)
    }
    private val extractor = FinancialEntityExtractor {
        ExtractedEntities(
            amounts = listOf(
                RoledAmount(
                    amount = Money(6420, Currency.EGP),
                    role = AmountRole.TRANSACTION_AMOUNT,
                    token = "64.20",
                    currencyToken = "EGP",
                    start = body.indexOf("64.20"),
                    end = body.indexOf("64.20") + 5,
                ),
            ),
            resolution = AmountResolution.RESOLVED,
            merchant = "Harbor Cafe",
        )
    }

    @Test
    fun `a replacement model understands an unverified bank and cannot post`() {
        val unknown = FinancialSmsIntelligence.replacing(classifier, extractor).assess(SmsText("01005551234", body))
        assertEquals(FinancialEventType.CARD_PURCHASE, unknown.classification.eventType)
        assertEquals(93, unknown.classification.confidence)
        assertEquals(Money(6420, Currency.EGP), unknown.entities.amount)
        assertEquals("Harbor Cafe", unknown.entities.merchant)
        assertEquals(DiscoveryStatus.UNKNOWN, unknown.discovery.status)
        assertEquals("unknown_institution", unknown.reviewHold())
        assertFalse(unknown.postable)
    }

    @Test
    fun `the same replacement posts only after the sender is a verified record`() {
        val verified = FinancialSmsIntelligence.replacing(
            classifier,
            extractor,
            senders = listOf(RegisteredSender("example.ferry-bank", "Ferry Bank", setOf("FERRY"))),
        ).assess(SmsText("FERRY", body))
        assertEquals(FinancialEventType.CARD_PURCHASE, verified.classification.eventType)
        assertEquals("example.ferry-bank", verified.discovery.verifiedInstitution?.institutionId)
        assertTrue(verified.postable)
    }
}
