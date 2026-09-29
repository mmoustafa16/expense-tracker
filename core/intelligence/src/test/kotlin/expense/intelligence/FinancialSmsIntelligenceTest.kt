package expense.intelligence

import expense.money.Currency
import expense.money.Money
import expense.parse.AmountResolution
import expense.parse.AmountRole
import expense.parse.Direction
import expense.parse.FinancialEventType
import expense.parse.RoledAmount
import expense.parse.SpendEffect
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
    fun `a handset stays unknown and an alphanumeric sender is not an institution on its own`() {
        assertTrue(InstitutionBootstrap.records.isEmpty())
        assertTrue(InstitutionCatalog.bundled().isEmpty())
        val handset = intelligence.assess(SmsText("01005551234", "Charged EGP 10.00 at Shop"))
        assertEquals(DiscoveryStatus.UNKNOWN, handset.discovery.status)
        assertTrue(handset.discovery.candidates.isEmpty())
        assertFalse(handset.postable)
        listOf("CIB", "ALEXBANK", "VodafoneCash").forEach { sender ->
            val decision = intelligence.assess(SmsText(sender, "Charged EGP 10.00 at Shop"))
            assertEquals(DiscoveryStatus.KNOWN, decision.discovery.status, sender)
            assertEquals(senderInstitutionId(sender), decision.discovery.candidates.single().institutionId, sender)
            assertNull(decision.discovery.verifiedInstitution, sender)
            assertEquals("unverified_institution", decision.reviewHold(), sender)
            assertFalse(decision.postable, sender)
        }
    }

    @Test
    fun `a sender that keeps reporting completed movements earns the right to post`() {
        val history = MutableSenderEvidenceLedger()
        val learning = FinancialSmsIntelligence.deterministic(senderEvidence = history)
        val bodies = listOf(
            "Purchase of EGP 120.50 from Talabat on card ****4229",
            "Cash withdrawal EGP 200.00 at ATM from card ****4229",
            "Transferred EGP 500.00 to Sam from card ****4229",
        )
        bodies.forEach { body ->
            val seen = learning.assess(SmsText("NEWBANK", body))
            history.observe(
                sender = "NEWBANK",
                eventType = seen.classification.eventType,
                moneyMovement = seen.state.moneyMovement,
                instrument = seen.entities.instrument,
            )
        }
        val decision = learning.assess(SmsText("NEWBANK", "Purchase of EGP 30.00 from Shop on card ****4229"))
        assertEquals("newbank", decision.discovery.verifiedInstitution?.institutionId)
        assertEquals(DiscoverySourceKind.INSTITUTIONAL_SENDER, decision.discovery.verifiedInstitution?.source)
        assertTrue(decision.postable)
    }

    @Test
    fun `a service channel repeating one non-financial message never becomes an institution`() {
        val history = MutableSenderEvidenceLedger()
        val learning = FinancialSmsIntelligence.deterministic(senderEvidence = history)
        repeat(8) {
            val seen = learning.assess(SmsText("HOSPITAL", "Your appointment is confirmed for tomorrow"))
            history.observe(
                sender = "HOSPITAL",
                eventType = seen.classification.eventType,
                moneyMovement = seen.state.moneyMovement,
                instrument = seen.entities.instrument,
            )
        }
        val decision = learning.assess(SmsText("HOSPITAL", "Your appointment is confirmed for tomorrow"))
        assertNull(decision.discovery.verifiedInstitution)
        assertEquals(RoutingOutcome.IGNORE, decision.routing.outcome)
        assertNull(decision.reviewHold())
    }

    @Test
    fun `an explicit card mask names the instrument and a different address stays different`() {
        val decision = intelligence.assess(SmsText("CIB", "Charged EGP 10.00 at Shop on card ****4229"))
        assertEquals("cib", decision.discovery.candidates.single().institutionId)
        assertEquals("CIB", decision.discovery.candidates.single().displayName)
        assertEquals("4229", decision.entities.accountMask)
        val other = intelligence.assess(SmsText("CIB-EG", "Charged EGP 10.00 at Shop on card ****1008"))
        assertEquals("cibeg", other.discovery.candidates.single().institutionId)
        assertEquals("CIB-EG", other.discovery.candidates.single().displayName)
        assertEquals("1008", other.entities.accountMask)
    }

    @Test
    fun `a registered sender is known and an unknown sender is not invented`() {
        val identified = verified.assess(SmsText("TESTBANK", "Charged EGP 10.00 at Shop"))
        assertEquals(DiscoveryStatus.KNOWN, identified.discovery.status)
        assertEquals("example.test-bank", identified.discovery.verifiedInstitution?.institutionId)
        assertEquals(90, identified.discovery.candidates.single().confidence)
        val stranger = intelligence.assess(SmsText("01005551234", "Charged EGP 10.00 at Shop"))
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
        assertEquals(
            setOf("example.bank-a", "example.bank-b"),
            decision.discovery.candidates.map { it.institutionId }.toSet(),
        )
        assertNull(decision.discovery.verifiedInstitution)
        assertEquals(FinancialEventType.CARD_PURCHASE, decision.classification.eventType)
        assertEquals("ambiguous_institution", decision.reviewHold())
        assertFalse(decision.postable)
    }

    @Test
    fun `card purchases with different wording share amount currency and merchant`() {
        val first = intelligence.assess(SmsText("X", "Your card was charged EGP 120.50 at Talabat on 15/01/2026"))
        val second = intelligence.assess(SmsText("X", "Purchase of EGP 120.50 from Talabat"))
        listOf(first, second).forEach { decision ->
            assertEquals(FinancialEventType.CARD_PURCHASE, decision.classification.eventType)
            assertEquals(Money(12050, Currency.EGP), decision.entities.amount)
            assertEquals(Currency.EGP, decision.entities.currency)
            assertEquals("Talabat", decision.entities.merchant)
            assertEquals(DiscoveryStatus.UNKNOWN, decision.discovery.status)
            assertEquals("unknown_institution", decision.reviewHold())
            assertFalse(decision.postable)
        }
        assertEquals(LocalDateTime.of(2026, 1, 15, 0, 0), first.entities.occurredAt)
        val posted = verified.assess(SmsText("TESTBANK", "Purchase of EGP 120.50 from Talabat"))
        assertTrue(posted.postable)
        assertEquals(SpendEffect.SPEND, posted.event.spendEffect)
        assertEquals("example.test-bank", posted.discovery.verifiedInstitution?.institutionId)
    }

    @Test
    fun `transfer withdrawal refund and fee keep their amounts across wording`() {
        val transfers = listOf(
            "Transferred EGP 500.00 to Sam",
            "Transfer of EGP 500.00 to Sam",
        ).map { intelligence.assess(SmsText("X", it)) }
        transfers.forEach { decision ->
            assertEquals(FinancialEventType.BANK_TRANSFER, decision.classification.eventType)
            assertEquals(Money(50000, Currency.EGP), decision.entities.amount)
            assertEquals(Direction.DEBIT, decision.entities.direction)
            assertEquals(SpendEffect.TRANSFER_EXTERNAL, decision.event.spendEffect)
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
            assertEquals(FinancialEventType.CASH_WITHDRAWAL, decision.classification.eventType)
            assertEquals(Money(20000, Currency.EGP), decision.entities.amount)
            assertEquals(SpendEffect.SPEND, decision.event.spendEffect)
            assertFalse(decision.postable)
        }

        val refund = intelligence.assess(SmsText("X", "Refund of EGP 75.00 from Shop"))
        assertEquals(FinancialEventType.REFUND, refund.classification.eventType)
        assertEquals(Money(7500, Currency.EGP), refund.entities.amount)
        assertEquals("Shop", refund.entities.merchant)
        assertEquals(Direction.CREDIT, refund.entities.direction)
        assertEquals(SpendEffect.SPEND_REVERSAL, refund.event.spendEffect)
        assertFalse(refund.postable)

        val fee = intelligence.assess(SmsText("X", "A service fee of EGP 5.00 was applied"))
        assertEquals(FinancialEventType.FEE, fee.classification.eventType)
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
        assertEquals(AmountResolution.RESOLVED, balanceLeft.entities.resolution)
        assertEquals(DiscoveryStatus.UNKNOWN, balanceLeft.discovery.status)
        assertFalse(balanceLeft.postable)
    }

    @Test
    fun `balance otp and promotion never become a transaction or a review item`() {
        val skipped = listOf(
            "Your available balance is EGP 1,250.00",
            "Your OTP is 482193",
            "OTP 482193 to confirm payment of EGP 20",
            "Save EGP 50 this weekend. Use code 20",
        ).map { intelligence.assess(SmsText("X", it)) }
        assertEquals(
            listOf(
                FinancialEventType.BALANCE_NOTIFICATION,
                FinancialEventType.NOT_FINANCIAL,
                FinancialEventType.NOT_FINANCIAL,
                FinancialEventType.NOT_FINANCIAL,
            ),
            skipped.map { it.classification.eventType },
        )
        skipped.forEach { decision ->
            assertEquals(DiscoveryStatus.UNKNOWN, decision.discovery.status)
            assertFalse(decision.postable)
            assertEquals(RoutingOutcome.IGNORE, decision.routing.outcome)
            assertNull(decision.reviewHold())
            assertFalse(decision.event.moneyMovement)
        }
    }

    @Test
    fun `ambiguous wording missing amount and a contradictory amount stay in review`() {
        val ambiguous = verified.assess(SmsText("TESTBANK", "Refunded or reversed EGP 40.00 from Shop"))
        assertTrue(ambiguous.classification.ambiguous)
        assertEquals("ambiguous_meaning", ambiguous.reviewHold())
        assertFalse(ambiguous.postable)

        val missing = verified.assess(SmsText("TESTBANK", "Your card ****4229 was charged at Talabat"))
        assertEquals(FinancialEventType.CARD_PURCHASE, missing.classification.eventType)
        assertNull(missing.entities.amount)
        assertEquals("amount_missing", missing.reviewHold())
        assertFalse(missing.postable)

        val lying = FinancialSmsIntelligence(
            discovery = BankDiscovery { BankDiscoveryResult.unknown() },
            classifier = SemanticTransactionClassifier.bundled(),
            extractor = FinancialEntityExtractor {
                ExtractedEntities(
                    amounts = listOf(
                        RoledAmount(
                            amount = Money(99900, Currency.EGP),
                            role = AmountRole.TRANSACTION_AMOUNT,
                            token = "999.00",
                            currencyToken = "EGP",
                            start = 0,
                            end = 6,
                        ),
                    ),
                    resolution = AmountResolution.RESOLVED,
                    merchant = "Shop",
                )
            },
            validator = DeterministicTransactionValidator(),
        )
        val contradictory = lying.assess(SmsText("X", "Charged EGP 20.00 at Shop"))
        assertTrue(contradictory.validation.contradictions.contains("amount_not_in_message"))
        assertFalse(contradictory.postable)
    }
}
