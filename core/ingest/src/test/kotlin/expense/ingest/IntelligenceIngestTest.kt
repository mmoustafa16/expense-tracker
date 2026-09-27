package expense.ingest

import expense.ingest.fixture.SyntheticBankProfile
import expense.intelligence.AmountRole
import expense.intelligence.DeterministicBankIdentifier
import expense.intelligence.DeterministicTransactionClassifier
import expense.intelligence.DeterministicTransactionValidator
import expense.intelligence.ExtractedEntities
import expense.intelligence.FinancialEntityExtractor
import expense.intelligence.FinancialSmsIntelligence
import expense.ledger.SpendPolicy
import expense.money.Currency
import expense.money.Money
import expense.parse.BankRegistry
import expense.parse.ParseStatus
import expense.parse.TransactionKind
import expense.parse.VerifiedBankCatalog
import expense.sms.InboundSms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class IntelligenceIngestTest {
    @Test
    fun `a validated purchase posts without a bank template and a replay does not duplicate it`() {
        val pipeline = IngestPipeline(ids = IntelligenceIds())
        val first = pipeline.ingest(sms("1", "Your card was charged EGP 120.50 at Talabat on 02/03/2026 09:15"))
        assertEquals(ParseStatus.PARSED, first.status)
        assertTrue(first.posted)
        assertFalse(first.matchedProfile)
        assertEquals("unknown", first.state.transactions.single().institutionId)
        assertEquals(TransactionKind.PURCHASE, first.state.transactions.single().kind)
        assertEquals(Money(12050, Currency.EGP), first.state.transactions.single().amount)
        assertEquals("Talabat", first.state.transactions.single().merchantRaw)
        assertEquals(12050L, SpendPolicy.signedMinor(first.state.transactions.single()))
        assertTrue(first.state.reviewQueue().isEmpty())
        assertTrue(VerifiedBankCatalog.registry().profiles.isEmpty())

        val replay = pipeline.ingest(sms("1", "Your card was charged EGP 120.50 at Talabat on 02/03/2026 09:15"), first.state)
        assertTrue(replay.alreadyIngested)
        assertEquals(1, replay.state.transactions.size)
    }

    @Test
    fun `balance otp promotion ambiguous and missing amount do not post`() {
        val pipeline = IngestPipeline(ids = IntelligenceIds())
        val ignored = listOf(
            "Your available balance is EGP 1,250.00",
            "Your OTP is 482193",
            "Save EGP 50 this weekend. Use code 20",
        )
        ignored.forEach { body ->
            val result = pipeline.ingest(sms(body, body))
            assertEquals(ParseStatus.IGNORED_NOT_BANK, result.status)
            assertTrue(result.state.transactions.isEmpty())
            assertTrue(result.state.reviewQueue().isEmpty())
        }
        val ambiguous = pipeline.ingest(sms("amb", "Refunded or reversed EGP 40.00 from Shop"))
        assertEquals(ParseStatus.UNSUPPORTED, ambiguous.status)
        assertTrue(ambiguous.state.transactions.isEmpty())
        assertEquals(1, ambiguous.state.reviewQueue().size)

        val missing = pipeline.ingest(sms("miss", "Your card was charged at Talabat"))
        assertEquals(ParseStatus.UNSUPPORTED, missing.status)
        assertTrue(missing.state.transactions.isEmpty())
        assertEquals(1, missing.state.reviewQueue().size)
    }

    @Test
    fun `a contradictory extraction stays in review`() {
        val lying = FinancialSmsIntelligence(
            banks = DeterministicBankIdentifier(emptyList()),
            classifier = DeterministicTransactionClassifier(),
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
        val result = IngestPipeline(ids = IntelligenceIds(), intelligence = lying).ingest(
            sms("bad", "Charged EGP 20.00 at Shop"),
        )
        assertEquals(ParseStatus.UNSUPPORTED, result.status)
        assertFalse(result.posted)
        assertTrue(result.state.transactions.isEmpty())
        assertEquals(1, result.state.reviewQueue().size)
    }

    @Test
    fun `a verified template still posts when it agrees with the message`() {
        val pipeline = IngestPipeline(BankRegistry(listOf(SyntheticBankProfile.create())), IntelligenceIds())
        val result = pipeline.ingest(
            sms("tpl", "TB|purchase|EGP|10.00|Shop|4242|S1|15/01/2026 10:00", SyntheticBankProfile.SENDER),
        )
        assertEquals(ParseStatus.PARSED, result.status)
        assertTrue(result.posted)
        assertEquals(SyntheticBankProfile.ID, result.state.transactions.single().institutionId)
        assertEquals(1, result.state.transactions.size)
        assertTrue(VerifiedBankCatalog.registry().profiles.isEmpty())
    }

    private fun sms(id: String, body: String, sender: String = "SHOP"): InboundSms {
        return InboundSms(
            sender = sender,
            body = body,
            providerMessageId = id,
            receivedAt = Instant.parse("2026-03-02T07:15:00Z"),
        )
    }
}

private class IntelligenceIds : IdGenerator {
    private var next = 0

    override fun newId(): String = "id-${next++}"
}
