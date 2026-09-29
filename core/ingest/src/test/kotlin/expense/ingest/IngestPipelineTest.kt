package expense.ingest

import expense.categories.CategoryRule
import expense.categories.CategorySource
import expense.categories.MatchType
import expense.categories.RuleSource
import expense.ingest.fixture.SyntheticBankProfile
import expense.ledger.Correction
import expense.ledger.CorrectionField
import expense.ledger.EvidenceRole
import expense.ledger.LedgerState
import expense.ledger.SpendPolicy
import expense.ledger.TransactionStatus
import expense.merchants.AliasType
import expense.money.Currency
import expense.parse.AccountKind
import expense.parse.BankProfile
import expense.parse.BankRegistry
import expense.parse.Direction
import expense.parse.ParseStatus
import expense.parse.SmsTemplate
import expense.parse.TemplateExtractor
import expense.parse.TemplateLanguage
import expense.parse.TransactionKind
import expense.sms.InboundSms
import expense.sms.SmsSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class EmptyRegistryTest {
    private val pipeline = IngestPipeline(ids = SequenceIds())

    @Test
    fun `financial sms is unsupported and non financial sms drops the body`() {
        val financial = pipeline.ingest(sms("OTHER", "Debited EGP 20.00 for Shop", "15/01/2026 10:00"))
        assertEquals(ParseStatus.UNSUPPORTED, financial.status)
        assertTrue(financial.financial)
        assertTrue(!financial.matchedProfile)
        assertTrue(!financial.posted)
        assertEquals("Debited EGP 20.00 for Shop", financial.state.messages.single().body)
        assertTrue(financial.state.transactions.isEmpty())
        assertTrue(financial.state.accounts.isEmpty())
        assertEquals(listOf(financial.attempt), financial.state.reviewQueue())

        val chatter = pipeline.ingest(sms("OTHER", "See you at dinner", "15/01/2026 11:00"), financial.state)
        assertEquals(ParseStatus.IGNORED_NOT_BANK, chatter.status)
        assertTrue(!chatter.financial)
        assertNull(chatter.state.messages.last().body)
        val tally = IngestTally().add(financial).add(chatter)
        assertEquals(2, tally.scanned)
        assertEquals(1, tally.financial)
        assertEquals(0, tally.matchedProfile)
        assertEquals(1, tally.unsupported)
        assertEquals(0, tally.parsed)
        assertEquals(0, tally.posted)
        assertTrue(chatter.state.transactions.isEmpty())
    }
}

class SyntheticPipelineTest {
    private val profile = SyntheticBankProfile.create()
    private val registry = BankRegistry(listOf(profile))

    @Test
    fun `ambiguous senders are not guessed`() {
        val shared = BankRegistry(
            listOf(
                SyntheticBankProfile.create(id = "example.test-bank-a", senderIds = setOf("SHARED")),
                SyntheticBankProfile.create(id = "example.test-bank-b", senderIds = setOf("SHARED")),
            ),
        )
        val result = pipeline(shared).ingest(
            sms("SHARED", "TB|purchase|EGP|150.00|Coffee Shop|4242|REF1|15/01/2026 10:00", "15/01/2026 10:00"),
        )
        assertEquals(ParseStatus.AMBIGUOUS, result.status)
        assertNull(result.attempt?.profileId)
        assertEquals(1, result.state.messages.count { it.body != null })
        assertTrue(result.state.transactions.isEmpty())
        assertTrue(result.state.reviewQueue().isNotEmpty())
    }

    @Test
    fun `known sender without a template stays unsupported and creates no account`() {
        val result = pipeline().ingest(
            sms(SyntheticBankProfile.SENDER, "Debited EGP 50.00 for Shop", "15/01/2026 10:00"),
        )
        assertEquals(ParseStatus.UNSUPPORTED, result.status)
        assertEquals(SyntheticBankProfile.ID, result.attempt?.profileId)
        assertTrue(result.state.transactions.isEmpty())
        assertTrue(result.state.accounts.isEmpty())
        assertEquals("Debited EGP 50.00 for Shop", result.state.messages.single().body)
    }

    @Test
    fun `marketing from a known sender without a currency amount is ignored`() {
        val result = pipeline().ingest(
            sms(SyntheticBankProfile.SENDER, "Your one time code is 1234", "15/01/2026 10:00"),
        )
        assertEquals(ParseStatus.IGNORED_NOT_BANK, result.status)
        assertNull(result.state.messages.single().body)
        assertTrue(result.state.transactions.isEmpty())
    }

    @Test
    fun `english arabic and mixed templates post purchases`() {
        val state = ingestAll(
            "TB|purchase|EGP|1,234.50|Coffee Shop|4242|EN1|15/01/2026 10:00",
            "تيست|شراء|EGP|١٥٠٫٥٠|قهوة|4242|AR1|15/01/2026 10:05",
            "TB|mixed|EGP|88.00|كافيه Cafe|4242|MX1|15/01/2026 10:10",
        )
        assertEquals(listOf("en-purchase", "ar-purchase", "mixed-purchase"), state.attempts.map { it.templateId })
        assertEquals(listOf(123450L, 15050L, 8800L), state.transactions.map { it.amount.amountMinor })
        assertTrue(state.transactions.all { it.profileVersion == "1" && it.pipelineVersion == PipelineMetadata.VERSION })
        assertEquals(AccountKind.DEBIT_CARD, state.accounts.single().kind)
        assertEquals("4242", state.accounts.single().mask)
        assertEquals(SyntheticBankProfile.ID, state.accounts.single().institutionId)
    }

    @Test
    fun `low confidence and missing amount stay in review without an account`() {
        val low = pipeline().ingest(
            sms(SyntheticBankProfile.SENDER, "TB|maybe|EGP|10.00|X|4242|M1|15/01/2026 21:00", "15/01/2026 21:00"),
        )
        assertEquals(ParseStatus.LOW_CONFIDENCE, low.status)
        assertTrue(low.state.transactions.isEmpty())
        assertTrue(low.state.accounts.isEmpty())

        val missing = pipeline().ingest(
            sms(SyntheticBankProfile.SENDER, "TB|no_amount|EGP||Shop|3333|N1|15/01/2026 22:00", "15/01/2026 22:00"),
        )
        assertEquals(ParseStatus.LOW_CONFIDENCE, missing.status)
        assertTrue(missing.state.accounts.isEmpty())
        assertTrue(missing.state.reviewQueue().isNotEmpty())
    }

    @Test
    fun `extractor failure does not post`() {
        val result = pipeline().ingest(
            sms(SyntheticBankProfile.SENDER, "TB|boom|EGP|10.00|X|4242|B1|15/01/2026 21:30", "15/01/2026 21:30"),
        )
        assertEquals(ParseStatus.FAILED, result.status)
        assertEquals("synthetic extractor failure", result.attempt?.error)
        assertTrue(result.state.transactions.isEmpty())
        assertEquals("TB|boom|EGP|10.00|X|4242|B1|15/01/2026 21:30", result.state.messages.single().body)
    }

    @Test
    fun `a thrown extractor is a failed review item`() {
        val exploding = BankProfile(
            id = "example.explode",
            version = "1",
            displayName = "Example Explode",
            senderIds = setOf("EXPLODE"),
            templates = listOf(
                SmsTemplate(
                    id = "explode",
                    languages = setOf(TemplateLanguage.EN),
                    minimumConfidence = 80,
                    extractor = TemplateExtractor { throw IllegalStateException("boom") },
                ),
            ),
        )
        val result = IngestPipeline(BankRegistry(listOf(exploding)), SequenceIds()).ingest(
            sms("EXPLODE", "anything EGP 1", "15/01/2026 10:00"),
        )
        assertEquals(ParseStatus.FAILED, result.status)
        assertEquals("boom", result.attempt?.error)
        assertTrue(result.state.transactions.isEmpty())
    }

    @Test
    fun `purchase and fee are two linked rows`() {
        val state = ingestAll(
            "TB|purchase_fee|EGP|200.00|Shop|2222|P1|15/01/2026 11:00|5.00",
        )
        val purchase = state.transactions.single { it.kind == TransactionKind.PURCHASE }
        val fee = state.transactions.single { it.kind == TransactionKind.FEE }
        assertEquals(20000L, purchase.amount.amountMinor)
        assertEquals(500L, fee.amount.amountMinor)
        assertEquals(purchase.id, fee.linkedTransactionId)
        assertEquals("fees", fee.categoryId)
        assertEquals(20500L, state.transactions.sumOf(SpendPolicy::signedMinor))
    }

    @Test
    fun `refunds reduce the refund month and reversals void the original`() {
        val refunded = ingestAll(
            "TB|purchase|EGP|150.00|Coffee Shop|4242|REF1|15/01/2026 10:00",
            "TB|refund|EGP|150.00|Coffee Shop|4242|REF1|02/02/2026 10:00",
        )
        val purchase = refunded.transactions.single { it.kind == TransactionKind.PURCHASE }
        val refund = refunded.transactions.single { it.kind == TransactionKind.REFUND }
        assertEquals(TransactionStatus.POSTED, purchase.status)
        assertEquals(purchase.id, refund.linkedTransactionId)
        assertTrue(refund.includeInSpend)
        assertEquals(Direction.CREDIT, refund.direction)
        assertEquals("2026-01", SpendPolicy.spendMonth(purchase).toString())
        assertEquals("2026-02", SpendPolicy.spendMonth(refund).toString())
        assertEquals(0L, refunded.transactions.sumOf(SpendPolicy::signedMinor))

        val reversed = ingestAll(
            "TB|purchase|EGP|150.00|Coffee Shop|4242|REF1|15/01/2026 10:00",
            "TB|reversal|EGP|150.00|Coffee Shop|4242|REF1|03/02/2026 10:00",
        )
        val original = reversed.transactions.single { it.kind == TransactionKind.PURCHASE }
        val reversal = reversed.transactions.single { it.kind == TransactionKind.REVERSAL }
        assertEquals(TransactionStatus.VOIDED, original.status)
        assertEquals(false, original.includeInSpend)
        assertEquals(false, reversal.includeInSpend)
        assertEquals(original.id, reversal.linkedTransactionId)
        assertTrue(reversed.evidence.any { it.role == EvidenceRole.REVERSAL_NOTICE })
        assertEquals(0L, reversed.transactions.sumOf(SpendPolicy::signedMinor))
    }

    @Test
    fun `failures transfers income cash and installments follow spend rules`() {
        val state = ingestAll(
            "TB|failed|EGP|80.00|Shop|4242|F1|15/01/2026 12:00",
            "TB|transfer_out|EGP|1000.00|Person|9988|T1|15/01/2026 13:00",
            "TB|transfer_in|EGP|400.00|Person|9988|T2|15/01/2026 13:05",
            "TB|income|EGP|5000.00|Employer|1001|S1|15/01/2026 08:00",
            "TB|cash|EGP|200.00|ATM|4242|C1|15/01/2026 18:00",
            "TB|installment|EGP|300.00|Store|4242|I1|15/01/2026 19:00|2|6",
        )
        val failed = state.transactions.single { it.kind == TransactionKind.FAILED }
        val out = state.transactions.single { it.kind == TransactionKind.TRANSFER_OUT }
        val incoming = state.transactions.single { it.kind == TransactionKind.TRANSFER_IN }
        val income = state.transactions.single { it.kind == TransactionKind.INCOME }
        val cash = state.transactions.single { it.kind == TransactionKind.CASH_WITHDRAWAL }
        val installment = state.transactions.single { it.kind == TransactionKind.INSTALLMENT }
        assertEquals(false, failed.includeInSpend)
        assertEquals("transfers", out.categoryId)
        assertEquals(false, out.includeInSpend)
        assertEquals(false, incoming.includeInSpend)
        assertEquals(Direction.CREDIT, income.direction)
        assertEquals(false, income.includeInSpend)
        assertEquals("cash", cash.categoryId)
        assertEquals(true, cash.includeInSpend)
        assertEquals(2, installment.installmentIndex)
        assertEquals(6, installment.installmentCount)
        assertEquals(30000L, installment.amount.amountMinor)
        assertEquals(true, installment.includeInSpend)
        assertEquals(50000L, state.transactions.sumOf(SpendPolicy::signedMinor))
    }

    @Test
    fun `foreign amount stays metadata and the ledger amount is the account debit`() {
        val state = ingestAll(
            "TB|foreign|EGP|500.00|Shop|4242|FX1|15/01/2026 20:00|USD|10.00",
        )
        val tx = state.transactions.single()
        assertEquals(50000L, tx.amount.amountMinor)
        assertEquals(Currency.EGP, tx.amount.currency)
        assertEquals(1000L, tx.foreignAmount?.amountMinor)
        assertEquals(Currency.USD, tx.foreignAmount?.currency)
    }

    @Test
    fun `keyword rules categorize a purchase`() {
        val rule = CategoryRule(
            id = "coffee-rule",
            matchType = MatchType.KEYWORD,
            pattern = "coffee",
            categoryId = "restaurants",
            priority = 10,
            source = RuleSource.SYSTEM,
        )
        val result = pipeline().ingest(
            sms(SyntheticBankProfile.SENDER, "TB|purchase|EGP|40.00|Coffee Shop|4242|K1|15/01/2026 10:00", "15/01/2026 10:00"),
            LedgerState(categoryRules = listOf(rule)),
        )
        val tx = result.state.transactions.single()
        assertEquals("restaurants", tx.categoryId)
        assertEquals(CategorySource.RULE, tx.categorySource)
    }

    @Test
    fun `sms source feeds the same pipeline`() {
        val source = SmsSource {
            listOf(sms(SyntheticBankProfile.SENDER, "TB|purchase|EGP|10.00|Shop|4242|S1|15/01/2026 10:00", "15/01/2026 10:00"))
        }
        val state = pipeline().ingestAll(source)
        assertEquals(1, state.transactions.size)
    }

    private fun pipeline(active: BankRegistry = registry) = IngestPipeline(active, SequenceIds())

    private fun ingestAll(vararg bodies: String): LedgerState {
        val pipe = pipeline()
        return bodies.fold(LedgerState.empty()) { state, body ->
            val stamp = body.substringAfterLast('|').take(16).let { tail ->
                val date = if (tail.length >= 16 && tail[2] == '/') tail else "15/01/2026 10:00"
                date
            }
            pipe.ingest(sms(SyntheticBankProfile.SENDER, body, stamp), state).state
        }
    }
}

class DuplicatePipelineTest {
    private val pipeline = IngestPipeline(BankRegistry(listOf(SyntheticBankProfile.create())), SequenceIds())

    @Test
    fun `same provider id is ingested once`() {
        val first = pipeline.ingest(purchase("REF1", "Coffee Shop", "p-1", "15/01/2026 10:00"))
        val second = pipeline.ingest(purchase("REF1", "Coffee Shop", "p-1", "15/01/2026 10:01"), first.state)
        assertTrue(second.alreadyIngested)
        assertEquals(1, second.state.messages.size)
        assertEquals(1, second.state.transactions.size)
    }

    @Test
    fun `same body hash inside the replay window does not post twice`() {
        val body = "TB|purchase|EGP|150.00|Coffee Shop|4242|REF1|15/01/2026 10:00"
        val first = pipeline.ingest(sms(SyntheticBankProfile.SENDER, body, "15/01/2026 10:00", "a"))
        val second = pipeline.ingest(sms(SyntheticBankProfile.SENDER, body, "15/01/2026 10:01", "b"), first.state)
        assertTrue(second.alreadyIngested)
        assertEquals(1, second.state.transactions.size)
        assertEquals(2, second.state.messages.size)
        assertTrue(second.state.evidence.any { it.role == EvidenceRole.DUPLICATE })
    }

    @Test
    fun `same reference and kind merges even when the merchant text differs`() {
        val first = pipeline.ingest(purchase("REF1", "Coffee Shop", "a", "15/01/2026 10:00"))
        val second = pipeline.ingest(purchase("REF1", "متجر", "b", "15/01/2026 10:01"), first.state)
        assertEquals(1, second.state.transactions.size)
        assertEquals("Coffee Shop", second.state.transactions.single().merchantRaw)
        assertTrue(second.state.evidence.any { it.role == EvidenceRole.DUPLICATE })
        assertTrue(second.state.possibleDuplicates.isEmpty())
    }

    @Test
    fun `near time same amount with different text is flagged and not merged`() {
        val first = pipeline.ingest(purchase("REF-A", "Coffee Shop", "a", "15/01/2026 10:00", "15/01/2026 10:00"))
        val second = pipeline.ingest(
            purchase("REF-B", "Bookstore", "b", "15/01/2026 10:02", "15/01/2026 10:02"),
            first.state,
        )
        assertEquals(2, second.state.transactions.size)
        assertEquals(1, second.state.possibleDuplicates.size)
    }

    private fun purchase(
        reference: String,
        merchant: String,
        providerId: String,
        received: String,
        occurred: String = "15/01/2026 10:00",
    ): InboundSms {
        return sms(
            SyntheticBankProfile.SENDER,
            "TB|purchase|EGP|150.00|$merchant|4242|$reference|$occurred",
            received,
            providerId,
        )
    }
}

class CorrectionReparseTest {
    @Test
    fun `corrections survive reparse and do not change the bank profile`() {
        val profile = SyntheticBankProfile.create()
        val registry = BankRegistry(listOf(profile))
        val templateIds = profile.templates.map { it.id }
        val pipeline = IngestPipeline(registry, SequenceIds())
        val posted = pipeline.ingest(
            sms(
                SyntheticBankProfile.SENDER,
                "TB|purchase|EGP|150.00|Coffee Shop|4242|REF1|15/01/2026 10:00",
                "15/01/2026 10:00",
                "p1",
            ),
        ).state
        val dedupKey = posted.transactions.single().dedupKey
        val renamed = pipeline.correct(
            posted,
            correction(dedupKey, CorrectionField.MERCHANT, "Coffee Shop", "Coffee Collective", "15/01/2026 12:00"),
        )
        val categorized = pipeline.correct(
            renamed,
            correction(dedupKey, CorrectionField.CATEGORY, null, "groceries", "15/01/2026 12:01"),
        )
        val corrected = categorized.transactions.single()
        assertEquals("Coffee Shop", corrected.merchantRaw)
        assertEquals("Coffee Collective", categorized.merchants.first { it.id == corrected.merchantId }.displayName)
        assertEquals("groceries", corrected.categoryId)
        assertEquals(CategorySource.USER, corrected.categorySource)
        assertSame(profile, registry.profiles.single())
        assertEquals(SyntheticBankProfile.VERSION, profile.version)
        assertEquals(templateIds, profile.templates.map { it.id })

        val followed = pipeline.ingest(
            sms(
                SyntheticBankProfile.SENDER,
                "TB|purchase|EGP|20.00|Coffee Shop|4242|REF2|16/01/2026 10:00",
                "16/01/2026 10:00",
                "p2",
            ),
            categorized,
        ).state
        assertEquals("groceries", followed.transactions.single { it.reference == "REF2" }.categoryId)

        val rebuilt = pipeline.reparse(followed)
        val original = rebuilt.transactions.single { it.reference == "REF1" }
        val later = rebuilt.transactions.single { it.reference == "REF2" }
        assertEquals("Coffee Collective", rebuilt.merchants.first { it.id == original.merchantId }.displayName)
        assertEquals("groceries", original.categoryId)
        assertEquals(CategorySource.USER, original.categorySource)
        assertEquals("groceries", later.categoryId)
        assertTrue(rebuilt.aliases.any { it.patternType == AliasType.EXACT_RAW && it.pattern == "Coffee Shop" })
        assertTrue(rebuilt.categoryRules.any { it.categoryId == "groceries" && it.source == RuleSource.USER })
        assertEquals(templateIds, registry.profiles.single().templates.map { it.id })
        assertEquals(SyntheticBankProfile.VERSION, registry.profiles.single().version)
    }

    private fun correction(
        dedupKey: String,
        field: CorrectionField,
        previous: String?,
        updated: String,
        at: String,
    ): Correction {
        return Correction(
            id = "$field-$at",
            dedupKey = dedupKey,
            field = field,
            previousValue = previous,
            updatedValue = updated,
            applyForward = true,
            createdAt = cairo(at),
        )
    }
}

private class SequenceIds : IdGenerator {
    private var next = 0
    override fun newId(): String = "id-${++next}"
}

private val civilFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")

private fun cairo(text: String): Instant {
    return CairoClock.instantFrom(LocalDateTime.parse(text, civilFormatter))
}

private fun sms(
    sender: String,
    body: String,
    received: String,
    providerMessageId: String? = null,
): InboundSms {
    return InboundSms(
        sender = sender,
        body = body,
        providerMessageId = providerMessageId,
        receivedAt = cairo(received),
    )
}
