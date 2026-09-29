package expense.intelligence

import expense.money.Currency
import expense.money.Money
import expense.parse.AmountRole
import expense.parse.Direction
import expense.parse.FinancialEventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Synthetic redacted messages. The sender is not a real bank and is not registered.
 * Understanding the transaction must not depend on naming the institution.
 */
class UnknownBankDiscoveryTest {
    private val intelligence = FinancialSmsIntelligence.deterministic()

    @Test
    fun `unknown institution still recognizes each event type and extracts what is present`() {
        val purchase = assess("Your card was used for EGP 450 at Talabat")
        assertEquals(DiscoveryStatus.UNKNOWN, purchase.discovery.status)
        assertTrue(purchase.discovery.candidates.isEmpty())
        assertEquals(FinancialEventType.CARD_PURCHASE, purchase.classification.eventType)
        assertEquals(Money(45000, Currency.EGP), purchase.entities.amount)
        assertEquals(Currency.EGP, purchase.entities.currency)
        assertEquals("Talabat", purchase.entities.merchant)
        assertEquals(Direction.DEBIT, purchase.entities.direction)
        assertEquals("unknown_institution", purchase.reviewHold())
        assertFalse(purchase.postable)

        val transfer = assess("Transferred EGP 500.00 to Sam")
        assertEquals(FinancialEventType.BANK_TRANSFER, transfer.classification.eventType)
        assertEquals(Money(50000, Currency.EGP), transfer.entities.amount)
        assertEquals(Direction.DEBIT, transfer.entities.direction)

        val withdrawal = assess("Cash withdrawal of EGP 200.00 at ATM")
        assertEquals(FinancialEventType.CASH_WITHDRAWAL, withdrawal.classification.eventType)
        assertEquals(Money(20000, Currency.EGP), withdrawal.entities.amount)

        val refund = assess("Refund of EGP 75.00 from Shop")
        assertEquals(FinancialEventType.REFUND, refund.classification.eventType)
        assertEquals(Money(7500, Currency.EGP), refund.entities.amount)
        assertEquals("Shop", refund.entities.merchant)
        assertEquals(Direction.CREDIT, refund.entities.direction)

        val reversal = assess("Reversed EGP 30.00 at Shop")
        assertEquals(FinancialEventType.REVERSAL, reversal.classification.eventType)
        assertEquals(Money(3000, Currency.EGP), reversal.entities.amount)
        assertEquals(Direction.CREDIT, reversal.entities.direction)

        val fee = assess("A service fee of EGP 5.00 was applied")
        assertEquals(FinancialEventType.FEE, fee.classification.eventType)
        assertEquals(Money(500, Currency.EGP), fee.entities.amount)

        val balance = assess("Your available balance is EGP 1,250.00")
        assertEquals(FinancialEventType.BALANCE_NOTIFICATION, balance.classification.eventType)
        assertEquals(AmountRole.AVAILABLE_BALANCE, balance.entities.amounts.single().role)
        assertEquals(Money(125000, Currency.EGP), balance.entities.balance)
        assertNull(balance.entities.amount)

        val otp = assess("Your OTP is 482193")
        assertEquals(FinancialEventType.NOT_FINANCIAL, otp.classification.eventType)
        assertNull(otp.entities.amount)

        val promotion = assess("Save EGP 50 this weekend. Use code 20")
        assertEquals(FinancialEventType.NOT_FINANCIAL, promotion.classification.eventType)

        listOf(purchase, transfer, withdrawal, refund, reversal, fee, balance, otp, promotion).forEach { decision ->
            assertEquals(DiscoveryStatus.UNKNOWN, decision.discovery.status)
            assertTrue(decision.discovery.candidates.isEmpty())
            assertNull(decision.discovery.verifiedInstitution)
            assertFalse(decision.postable)
        }
        listOf(balance, otp, promotion).forEach { decision ->
            assertEquals(RoutingOutcome.IGNORE, decision.routing.outcome)
            assertNull(decision.reviewHold())
        }
        listOf(purchase, transfer, withdrawal, refund, reversal, fee).forEach { decision ->
            assertEquals(RoutingOutcome.REVIEW, decision.routing.outcome)
            assertEquals("unknown_institution", decision.reviewHold())
        }
    }

    @Test
    fun `the same purchase is understood whether or not the institution is known`() {
        val body = "Your card was used for EGP 450 at Talabat"
        val unknown = intelligence.assess(SmsText("01005550000", body))
        val known = FinancialSmsIntelligence.deterministic(
            listOf(RegisteredSender("example.test-bank", "Example Test Bank", setOf("TESTBANK"))),
        ).assess(SmsText("TESTBANK", body))
        assertEquals(unknown.classification.eventType, known.classification.eventType)
        assertEquals(unknown.entities.amount, known.entities.amount)
        assertEquals(unknown.entities.merchant, known.entities.merchant)
        assertEquals(DiscoveryStatus.UNKNOWN, unknown.discovery.status)
        assertEquals(FinancialEventType.CARD_PURCHASE, unknown.classification.eventType)
        assertFalse(unknown.postable)
        assertEquals(DiscoveryStatus.KNOWN, known.discovery.status)
        assertTrue(known.postable)
    }

    @Test
    fun `metadata model and learned hints do not authorize a ledger post`() {
        val hint = DiscoveredInstitution(
            institutionId = "example.hint-bank",
            displayName = "Hint Bank",
            confidence = 70,
            source = DiscoverySourceKind.ON_DEVICE_MODEL,
            verified = true,
        )
        val hinted = FinancialSmsIntelligence(
            discovery = CompositeBankDiscovery(
                listOf(
                    PublicBankMetadata(),
                    OnDeviceModelDiscovery(),
                    LocalLearnedPatterns(),
                    object : BankDiscoverySource {
                        override val kind = DiscoverySourceKind.ON_DEVICE_MODEL
                        override fun discover(message: SmsText) = listOf(hint)
                    },
                ),
            ),
            classifier = SemanticTransactionClassifier.bundled(),
            extractor = DeterministicEntityExtractor(),
            validator = DeterministicTransactionValidator(),
        )
        val decision = hinted.assess(SmsText("HINT", "Your card was used for EGP 450 at Talabat"))
        assertEquals(DiscoveryStatus.KNOWN, decision.discovery.status)
        assertEquals("example.hint-bank", decision.discovery.candidates.single().institutionId)
        assertFalse(decision.discovery.candidates.single().verified)
        assertNull(decision.discovery.verifiedInstitution)
        assertEquals(FinancialEventType.CARD_PURCHASE, decision.classification.eventType)
        assertEquals("unverified_institution", decision.reviewHold())
        assertFalse(decision.postable)
        assertTrue(PublicBankMetadata().discover(SmsText("HINT", "body")).isEmpty())
        assertTrue(OnDeviceModelDiscovery().discover(SmsText("HINT", "body")).isEmpty())
        assertTrue(LocalLearnedPatterns().discover(SmsText("HINT", "body")).isEmpty())
    }

    @Test
    fun `a user confirmed sender names the institution and still cannot post`() {
        val confirmed = FinancialSmsIntelligence.deterministic(
            userConfirmed = listOf(UserConfirmedSender("example.confirmed-bank", "Confirmed Bank", "MYBANK")),
        )
        val decision = confirmed.assess(SmsText("MYBANK", "Your card was used for EGP 450 at Talabat"))
        assertEquals(DiscoveryStatus.KNOWN, decision.discovery.status)
        assertEquals("example.confirmed-bank", decision.discovery.candidates.single().institutionId)
        assertEquals(DiscoverySourceKind.USER_CONFIRMED_SENDER, decision.discovery.candidates.single().source)
        assertNull(decision.discovery.verifiedInstitution)
        assertEquals(FinancialEventType.CARD_PURCHASE, decision.classification.eventType)
        assertEquals(Money(45000, Currency.EGP), decision.entities.amount)
        assertEquals("unverified_institution", decision.reviewHold())
        assertFalse(decision.postable)
    }

    private fun assess(body: String): ClassificationDecision {
        return intelligence.assess(SmsText("01005550000", body))
    }
}
