package expense.ledger

import expense.categories.CategorySource
import expense.categories.NewCategory
import expense.money.Currency
import expense.money.Money
import expense.parse.AccountKind
import expense.parse.Direction
import expense.parse.ParseAttempt
import expense.parse.ParseStatus
import expense.parse.TransactionKind
import expense.sms.BodyHash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime

class LedgerEditsTest {
    @Test
    fun `renaming an account does not change its identity`() {
        val account = Account("a1", "bank-1", AccountKind.CREDIT_CARD, "4242", Currency.EGP)
        val state = LedgerState(accounts = listOf(account))
        val renamed = AccountNames.rename(state, "a1", "  Household card  ")
        val updated = renamed.accounts.single()
        assertEquals("Household card", updated.displayName)
        assertEquals(account.id, updated.id)
        assertEquals(account.institutionId, updated.institutionId)
        assertEquals(account.kind, updated.kind)
        assertEquals(account.mask, updated.mask)
        assertEquals(account.currency, updated.currency)
    }

    @Test
    fun `dismiss keeps the financial body and drops the review item`() {
        val body = "Charged EGP 20.00 at Shop"
        val state = reviewState(body)
        assertEquals(1, state.reviewQueue().size)
        val dismissed = ReviewDismissals.dismiss(state, "attempt-1")
        assertTrue(dismissed.reviewQueue().isEmpty())
        assertEquals(body, dismissed.messages.single().body)
    }

    @Test
    fun `manual post links a review item and does not describe a bank profile`() {
        val source = File("src/main/kotlin/expense/ledger/ManualLedger.kt").readText()
        assertFalse(source.contains("BankProfile"))
        assertFalse(source.contains("BankRegistry"))
        val body = "Charged EGP 20.00 at Shop"
        val state = reviewState(body)
        val posted = ManualLedger.post(
            state,
            ManualDraft(
                amount = Money(2000, Currency.EGP),
                direction = Direction.DEBIT,
                kind = TransactionKind.PURCHASE,
                occurredAt = Instant.parse("2026-05-01T08:00:00Z"),
                occurredCivil = LocalDateTime.of(2026, 5, 1, 10, 0),
                merchantRaw = "Shop",
                categoryId = "shopping",
                reference = "REF-9",
                institutionId = "bank-1",
                accountKind = AccountKind.DEBIT_CARD,
                accountMask = "1111",
                reviewAttemptId = "attempt-1",
                pipelineVersion = "1",
            ),
        ) { "manual-id" }
        val tx = posted.transactions.single()
        assertTrue(tx.manual)
        assertEquals("", tx.profileVersion)
        assertEquals("shopping", tx.categoryId)
        assertEquals(CategorySource.USER, tx.categorySource)
        assertEquals("sms-1", tx.smsId)
        assertEquals("REF-9", tx.reference)
        val account = posted.accounts.single()
        assertEquals("bank-1", account.institutionId)
        assertEquals(AccountKind.DEBIT_CARD, account.kind)
        assertEquals("1111", account.mask)
        assertEquals("", account.displayName)
        assertTrue(posted.reviewQueue().isEmpty())
        assertEquals(body, posted.messages.single().body)
        assertEquals(EvidenceRole.PRIMARY, posted.evidence.single().role)
    }

    @Test
    fun `manual account reuse keeps the display name`() {
        val existing = Account("a1", "bank-1", AccountKind.ACCOUNT, "9999", Currency.USD, displayName = "Travel")
        val posted = ManualLedger.post(
            LedgerState(accounts = listOf(existing)),
            ManualDraft(
                amount = Money(100, Currency.USD),
                direction = Direction.CREDIT,
                kind = TransactionKind.INCOME,
                occurredAt = Instant.parse("2026-05-02T08:00:00Z"),
                occurredCivil = LocalDateTime.of(2026, 5, 2, 10, 0),
                merchantRaw = null,
                categoryId = null,
                reference = null,
                institutionId = "bank-1",
                accountKind = AccountKind.ACCOUNT,
                accountMask = "9999",
                reviewAttemptId = null,
                pipelineVersion = "1",
            ),
        ) { "id-1" }
        assertEquals("a1", posted.transactions.single().accountId)
        assertEquals("Travel", posted.accounts.single().displayName)
        assertNull(posted.transactions.single().categoryId)
        assertEquals(1, posted.accounts.size)
    }

    @Test
    fun `custom category edits stay on the ledger`() {
        val added = LedgerCategories.add(
            LedgerState(),
            NewCategory("pets", "shopping", "Pets", "حيوانات", "pets", 61),
        )
        assertEquals("pets", added.categories.single().id)
        assertEquals(false, added.categories.single().system)
    }

    private fun reviewState(body: String): LedgerState {
        val receivedAt = Instant.parse("2026-05-01T07:00:00Z")
        return LedgerState(
            messages = listOf(
                StoredSms(
                    id = "sms-1",
                    sender = "LAB",
                    body = body,
                    bodyHash = BodyHash.sha256(body),
                    providerMessageId = "11",
                    receivedAt = receivedAt,
                ),
            ),
            attempts = listOf(
                ParseAttempt(
                    id = "attempt-1",
                    smsId = "sms-1",
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
        )
    }
}
