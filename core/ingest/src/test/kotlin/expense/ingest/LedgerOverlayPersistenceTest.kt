package expense.ingest

import expense.categories.Category
import expense.categories.CategorySource
import expense.categories.RuleSource
import expense.ingest.fixture.SyntheticBankProfile
import expense.ledger.Account
import expense.ledger.AccountNames
import expense.ledger.Correction
import expense.ledger.CorrectionField
import expense.ledger.LedgerState
import expense.ledger.ManualDraft
import expense.ledger.ManualLedger
import expense.ledger.ReviewDismissals
import expense.ledger.StoredSms
import expense.money.Currency
import expense.money.Money
import expense.parse.AccountKind
import expense.parse.BankRegistry
import expense.parse.Direction
import expense.parse.ParseAttempt
import expense.parse.ParseStatus
import expense.parse.TransactionKind
import expense.sms.BodyHash
import expense.sms.InboundSms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class LedgerOverlayPersistenceTest {
    @Test
    fun `custom category corrections do not change the empty production registry`() {
        val registry = BankRegistry.EMPTY
        val pipeline = IngestPipeline(registry, OverlayIds())
        val category = Category(
            id = "pets",
            parentId = "shopping",
            nameEn = "Pets",
            nameAr = "حيوانات",
            slug = "pets",
            sortOrder = 61,
            system = false,
        )
        val state = LedgerState(
            categories = listOf(category),
            transactions = listOf(manualTransaction(dedupKey = "dedup-1", merchantId = "merchant-1")),
            merchants = listOf(
                expense.merchants.Merchant("merchant-1", "Shop", "shop"),
            ),
        )
        val corrected = pipeline.correct(
            state,
            Correction(
                id = "c1",
                dedupKey = "dedup-1",
                field = CorrectionField.CATEGORY,
                previousValue = null,
                updatedValue = "pets",
                applyForward = true,
                createdAt = Instant.parse("2026-05-01T12:00:00Z"),
            ),
        )
        assertEquals("pets", corrected.transactions.single().categoryId)
        assertEquals(CategorySource.USER, corrected.transactions.single().categorySource)
        assertTrue(corrected.categoryRules.any { it.categoryId == "pets" && it.source == RuleSource.USER })
        assertSame(BankRegistry.EMPTY, registry)
        assertTrue(registry.profiles.isEmpty())
    }

    @Test
    fun `reparse keeps display names dismissals custom categories and manual rows`() {
        val body = "Charged EGP 20.00 at Shop"
        val receivedAt = Instant.parse("2026-05-01T07:00:00Z")
        val sms = StoredSms(
            id = "sms-1",
            sender = "LAB",
            body = body,
            bodyHash = BodyHash.sha256(body),
            providerMessageId = "11",
            receivedAt = receivedAt,
        )
        val account = Account(
            id = "acct-1",
            institutionId = "bank-1",
            kind = AccountKind.DEBIT_CARD,
            mask = "4242",
            currency = Currency.EGP,
            displayName = "Daily",
        )
        val category = Category("pets", null, "Pets", "حيوانات", "pets", 200, system = false)
        val base = LedgerState(
            messages = listOf(sms),
            attempts = listOf(
                ParseAttempt(
                    id = "attempt-1",
                    smsId = sms.id,
                    pipelineVersion = "1",
                    profileId = null,
                    profileVersion = null,
                    templateId = null,
                    status = ParseStatus.UNSUPPORTED,
                    confidence = null,
                    extraction = null,
                    error = null,
                ),
            ),
            accounts = listOf(account),
            categories = listOf(category),
        )
        val dismissed = ReviewDismissals.dismiss(base, "attempt-1")
        val withManual = ManualLedger.post(
            dismissed,
            ManualDraft(
                amount = Money(2000, Currency.EGP),
                direction = Direction.DEBIT,
                kind = TransactionKind.PURCHASE,
                occurredAt = receivedAt,
                occurredCivil = LocalDateTime.of(2026, 5, 1, 9, 0),
                merchantRaw = "Shop",
                categoryId = "pets",
                reference = "M-1",
                institutionId = "bank-1",
                accountKind = AccountKind.DEBIT_CARD,
                accountMask = "4242",
                reviewAttemptId = null,
                pipelineVersion = "1",
            ),
        ) { "manual-tx" }
        val pipeline = IngestPipeline(ids = OverlayIds())
        val rebuilt = pipeline.reparse(withManual)
        assertEquals("Daily", rebuilt.accounts.single { it.id == "acct-1" }.displayName)
        assertEquals("4242", rebuilt.accounts.single { it.id == "acct-1" }.mask)
        assertEquals("pets", rebuilt.categories.single().id)
        assertEquals(body, rebuilt.messages.single { it.providerMessageId == "11" }.body)
        assertTrue(rebuilt.reviewQueue().isEmpty())
        val manual = rebuilt.transactions.single { it.manual }
        assertEquals("pets", manual.categoryId)
        assertEquals("M-1", manual.reference)
        assertEquals(Money(2000, Currency.EGP), manual.amount)
        assertTrue(registryStillEmpty())
    }

    @Test
    fun `parsed account display names survive another ingest and reparse`() {
        val profile = SyntheticBankProfile.create()
        val registry = BankRegistry(listOf(profile))
        val pipeline = IngestPipeline(registry, OverlayIds())
        val first = pipeline.ingest(purchase("p1", "REF-1")).state
        val renamed = AccountNames.rename(first, first.accounts.single().id, "Spending")
        val second = pipeline.ingest(purchase("p2", "REF-2"), renamed).state
        assertEquals(listOf("Spending"), second.accounts.map { it.displayName })
        assertEquals(profile.id, second.accounts.single().institutionId)
        assertEquals(AccountKind.DEBIT_CARD, second.accounts.single().kind)
        assertEquals("4242", second.accounts.single().mask)
        val rebuilt = pipeline.reparse(second)
        assertEquals("Spending", rebuilt.accounts.single().displayName)
        assertEquals("4242", rebuilt.accounts.single().mask)
        assertEquals(profile.version, registry.profiles.single().version)
        assertEquals(profile.templates.map { it.id }, registry.profiles.single().templates.map { it.id })
    }

    private fun registryStillEmpty(): Boolean = BankRegistry.EMPTY.profiles.isEmpty()

    private fun purchase(providerId: String, reference: String): InboundSms {
        return InboundSms(
            sender = SyntheticBankProfile.SENDER,
            body = "TB|purchase|EGP|150.00|Coffee Shop|4242|$reference|15/01/2026 10:00",
            providerMessageId = providerId,
            receivedAt = cairo("15/01/2026 10:00"),
        )
    }

    private fun manualTransaction(dedupKey: String, merchantId: String) = expense.ledger.Transaction(
        id = "tx-1",
        dedupKey = dedupKey,
        smsId = "sms-x",
        institutionId = "bank-1",
        accountId = null,
        kind = TransactionKind.PURCHASE,
        status = expense.ledger.TransactionStatus.POSTED,
        amount = Money(100, Currency.EGP),
        direction = Direction.DEBIT,
        occurredAt = Instant.parse("2026-05-01T08:00:00Z"),
        occurredCivil = LocalDateTime.of(2026, 5, 1, 10, 0),
        occurredSource = expense.ledger.OccurredSource.MANUAL,
        merchantRaw = "Shop",
        merchantId = merchantId,
        categoryId = null,
        categorySource = CategorySource.UNCATEGORIZED,
        reference = null,
        balance = null,
        foreignAmount = null,
        duplicateOfId = null,
        linkedTransactionId = null,
        includeInSpend = true,
        pipelineVersion = "1",
        profileVersion = "",
        installmentIndex = null,
        installmentCount = null,
        manual = true,
    )

    private fun cairo(text: String): Instant {
        val formatter = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")
        return CairoClock.instantFrom(LocalDateTime.parse(text, formatter))
    }
}

private class OverlayIds : IdGenerator {
    private var next = 0
    override fun newId(): String = "id-${++next}"
}
