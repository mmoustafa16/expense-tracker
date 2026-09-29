package expense.intelligence

import expense.money.Currency
import expense.money.Money
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
        Classification(TransactionClass.CARD_PURCHASE, confidence = 93, ambiguous = false)
    }
    private val extractor = FinancialEntityExtractor {
        ExtractedEntities(
            amount = Money(6420, Currency.EGP),
            amountToken = "64.20",
            currency = Currency.EGP,
            currencyToken = "EGP",
            merchant = "Harbor Cafe",
            amountRole = AmountRole.TRANSACTION,
        )
    }

    @Test
    fun `a replacement model understands an unverified bank and cannot post`() {
        val unknown = FinancialSmsIntelligence.replacing(classifier, extractor).assess(SmsText("01005551234", body))
        assertEquals(TransactionClass.CARD_PURCHASE, unknown.classification.type)
        assertEquals(93, unknown.classification.confidence)
        assertEquals(Money(6420, Currency.EGP), unknown.entities.amount)
        assertEquals("Harbor Cafe", unknown.entities.merchant)
        assertEquals(DiscoveryStatus.UNKNOWN, unknown.discovery.status)
        assertEquals(ConfidenceLevel.MEDIUM, unknown.level)
        assertFalse(unknown.postable)
    }

    @Test
    fun `the same replacement posts only after the sender is a verified record`() {
        val verified = FinancialSmsIntelligence.replacing(
            classifier,
            extractor,
            senders = listOf(RegisteredSender("example.ferry-bank", "Ferry Bank", setOf("FERRY"))),
        ).assess(SmsText("FERRY", body))
        assertEquals(TransactionClass.CARD_PURCHASE, verified.classification.type)
        assertEquals("example.ferry-bank", verified.discovery.verifiedInstitution?.institutionId)
        assertEquals(ConfidenceLevel.HIGH, verified.level)
        assertTrue(verified.postable)
    }
}
