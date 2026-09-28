package expense.intelligence

import expense.money.Currency
import expense.money.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class FinancialSmsIntelligenceTest {
    private val intelligence = FinancialSmsIntelligence.deterministic()
    private val verified = FinancialSmsIntelligence.deterministic(
        listOf(RegisteredSender("example.test-bank", "Example Test Bank", setOf("TESTBANK"))),
    )

    @Test
    fun `an unregistered sender stays unknown`() {
        assertTrue(InstitutionBootstrap.records.isEmpty())
        listOf("CIB", "ALEXBANK", "Vodafone", "SOMEBANK").forEach { sender ->
            val decision = intelligence.assess(SmsText(sender, "Charged EGP 10.00 at Shop"))
            assertEquals(DiscoveryStatus.UNKNOWN, decision.discovery.status, sender)
            assertTrue(decision.discovery.candidates.isEmpty())
            assertNull(decision.discovery.verifiedInstitution)
        }
    }

    @Test
    fun `a registered sender is known and an unknown sender is not invented`() {
        val identified = verified.assess(SmsText("TESTBANK", "Charged EGP 10.00 at Shop"))
        assertEquals(DiscoveryStatus.KNOWN, identified.discovery.status)
        assertEquals("example.test-bank", identified.discovery.verifiedInstitution?.institutionId)
        assertEquals(90, identified.discovery.candidates.single().confidence)
        val stranger = intelligence.assess(SmsText("SOMEBANK", "Charged EGP 10.00 at Shop"))
        assertEquals(DiscoveryStatus.UNKNOWN, stranger.discovery.status)
        assertTrue(stranger.discovery.candidates.isEmpty())
    }

    @Test
    fun `two verified institutions for one sender stay ambiguous`() {
        val shared = FinancialSmsIntelligence.deterministic(
            listOf(
                RegisteredSender("example.bank-a", "Bank A", setOf("SHARED")),
                RegisteredSender("example.bank-b", "Bank B", setOf("SHARED")),
            ),
        )
        val decision = shared.assess(SmsText("SHARED", "Charged EGP 10.00 at Shop"))
        assertEquals(DiscoveryStatus.AMBIGUOUS, decision.discovery.status)
        assertEquals(setOf("example.bank-a", "example.bank-b"), decision.discovery.candidates.map { it.institutionId }.toSet())
        assertNull(decision.discovery.verifiedInstitution)
        assertEquals(TransactionClass.CARD_PURCHASE, decision.classification.type)
        assertEquals(ConfidenceLevel.MEDIUM, decision.level)
        assertFalse(decision.postable)
    }

    @Test
    fun `card purchases with different wording share amount currency and merchant`() {
        val first = intelligence.assess(SmsText("X", "Your card was charged EGP 120.50 at Talabat on 15/01/2026"))
        val second = intelligence.assess(SmsText("X", "Purchase of EGP 120.50 from Talabat"))
        listOf(first, second).forEach { decision ->
            assertEquals(TransactionClass.CARD_PURCHASE, decision.classification.type)
            assertEquals(Money(12050, Currency.EGP), decision.entities.amount)
            assertEquals(Currency.EGP, decision.entities.currency)
            assertEquals("Talabat", decision.entities.merchant)
            assertEquals(DiscoveryStatus.UNKNOWN, decision.discovery.status)
            assertEquals(ConfidenceLevel.MEDIUM, decision.level)
            assertFalse(decision.postable)
        }
        assertNull(first.entities.occurredAt)
        val posted = verified.assess(SmsText("TESTBANK", "Purchase of EGP 120.50 from Talabat"))
        assertEquals(ConfidenceLevel.HIGH, posted.level)
        assertTrue(posted.postable)
        assertEquals("example.test-bank", posted.discovery.verifiedInstitution?.institutionId)
    }

    @Test
    fun `transfer withdrawal refund and fee keep their amounts across wording`() {
        val transfers = listOf(
            "Transferred EGP 500.00 to Sam",
            "Transfer of EGP 500.00 to Sam",
        ).map { intelligence.assess(SmsText("X", it)) }
        transfers.forEach { decision ->
            assertEquals(TransactionClass.TRANSFER, decision.classification.type)
            assertEquals(Money(50000, Currency.EGP), decision.entities.amount)
            assertEquals(MoneyDirection.DEBIT, decision.entities.direction)
            assertEquals(DiscoveryStatus.UNKNOWN, decision.discovery.status)
            assertFalse(decision.postable)
        }
        val postedTransfer = verified.assess(SmsText("TESTBANK", "Transferred EGP 500.00 to Sam"))
        assertTrue(postedTransfer.postable)

        val withdrawals = listOf(
            "Cash withdrawal EGP 200.00 at ATM",
            "Withdrew EGP 200.00 from the ATM",
        ).map { intelligence.assess(SmsText("X", it)) }
        withdrawals.forEach { decision ->
            assertEquals(TransactionClass.CASH_WITHDRAWAL, decision.classification.type)
            assertEquals(Money(20000, Currency.EGP), decision.entities.amount)
            assertFalse(decision.postable)
        }

        val refund = intelligence.assess(SmsText("X", "Refund of EGP 75.00 from Shop"))
        assertEquals(TransactionClass.REFUND, refund.classification.type)
        assertEquals(Money(7500, Currency.EGP), refund.entities.amount)
        assertEquals("Shop", refund.entities.merchant)
        assertEquals(MoneyDirection.CREDIT, refund.entities.direction)
        assertFalse(refund.postable)

        val fee = intelligence.assess(SmsText("X", "A service fee of EGP 5.00 was applied"))
        assertEquals(TransactionClass.FEE, fee.classification.type)
        assertEquals(Money(500, Currency.EGP), fee.entities.amount)
        assertFalse(fee.postable)
    }

    @Test
    fun `optional fields are copied only when the message contains them`() {
        val decision = intelligence.assess(
            SmsText("X", "Charged EGP 10.00 at Shop on 15/01/2026 14:30 ref AB12 card ending 4242"),
        )
        assertEquals(LocalDateTime.of(2026, 1, 15, 14, 30), decision.entities.occurredAt)
        assertEquals("AB12", decision.entities.reference)
        assertEquals("4242", decision.entities.accountMask)
        assertEquals("Shop", decision.entities.merchant)
        val balanceLeft = intelligence.assess(
            SmsText("X", "Charged EGP 40.00 at Shop. Available balance EGP 900.00"),
        )
        assertEquals(Money(4000, Currency.EGP), balanceLeft.entities.amount)
        assertEquals(Money(90000, Currency.EGP), balanceLeft.entities.balance)
        assertEquals(DiscoveryStatus.UNKNOWN, balanceLeft.discovery.status)
        assertFalse(balanceLeft.postable)
    }

    @Test
    fun `balance otp and promotion never become a transaction`() {
        val skipped = listOf(
            "Your available balance is EGP 1,250.00",
            "Your OTP is 482193",
            "OTP 482193 to confirm payment of EGP 20",
            "Save EGP 50 this weekend. Use code 20",
        ).map { intelligence.assess(SmsText("X", it)) }
        assertEquals(
            listOf(
                TransactionClass.BALANCE_NOTIFICATION,
                TransactionClass.OTP,
                TransactionClass.OTP,
                TransactionClass.PROMOTION,
            ),
            skipped.map { it.classification.type },
        )
        assertTrue(
            skipped.all {
                it.discovery.status == DiscoveryStatus.UNKNOWN &&
                    it.validation.ledgerForbidden &&
                    !it.postable &&
                    it.level == ConfidenceLevel.LOW
            },
        )
    }

    @Test
    fun `ambiguous wording missing amount and a contradictory amount stay in review`() {
        val ambiguous = intelligence.assess(SmsText("X", "Refunded or reversed EGP 40.00 from Shop"))
        assertTrue(ambiguous.classification.ambiguous)
        assertEquals(ConfidenceLevel.MEDIUM, ambiguous.level)
        assertFalse(ambiguous.postable)

        val missing = intelligence.assess(SmsText("X", "Your card was charged at Talabat"))
        assertEquals(TransactionClass.CARD_PURCHASE, missing.classification.type)
        assertNull(missing.entities.amount)
        assertEquals(ConfidenceLevel.MEDIUM, missing.level)
        assertFalse(missing.postable)

        val lying = FinancialSmsIntelligence(
            discovery = BankDiscovery { BankDiscoveryResult.unknown() },
            classifier = SemanticTransactionClassifier.bundled(),
            extractor = FinancialEntityExtractor {
                ExtractedEntities(
                    amount = Money(99900, Currency.EGP),
                    amountToken = "999.00",
                    currency = Currency.EGP,
                    currencyToken = "EGP",
                    merchant = "Shop",
                    amountRole = AmountRole.TRANSACTION,
                )
            },
            validator = DeterministicTransactionValidator(),
        )
        val contradictory = lying.assess(SmsText("X", "Charged EGP 20.00 at Shop"))
        assertTrue(contradictory.validation.contradictions.contains("amount_not_in_message"))
        assertEquals(ConfidenceLevel.MEDIUM, contradictory.level)
        assertFalse(contradictory.postable)
    }
}
