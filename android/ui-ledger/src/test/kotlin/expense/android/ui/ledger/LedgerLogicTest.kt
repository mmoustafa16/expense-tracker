package expense.android.ui.ledger

import expense.android.ui.common.UiResult
import expense.categories.Category
import expense.categories.CategorySeed
import expense.categories.CategorySource
import expense.ledger.Account
import expense.ledger.LedgerState
import expense.ledger.OccurredSource
import expense.ledger.StoredSms
import expense.ledger.Transaction
import expense.ledger.TransactionStatus
import expense.money.Currency
import expense.money.Money
import expense.parse.AccountKind
import expense.parse.Direction
import expense.parse.TransactionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime

class LedgerLogicTest {
    @Test
    fun `banks group accounts and leave masked transactions on their account`() {
        val named = Account("acct-1", "bank-1", AccountKind.CREDIT_CARD, "4242", Currency.EGP, "Household")
        val plain = Account("acct-2", "bank-1", AccountKind.ACCOUNT, "9999", Currency.EGP)
        val other = Account("acct-3", "bank-2", AccountKind.WALLET, "1", Currency.USD, "Travel")
        val state = LedgerState(
            accounts = listOf(other, plain, named),
            transactions = listOf(
                tx("t-named", "bank-1", "acct-1"),
                tx("t-loose", "bank-1", null),
                tx("t-other", "bank-2", "acct-3"),
            ),
        )
        val tree = LedgerTreeBuilder.build(state)
        assertEquals(listOf("bank-1", "bank-2"), tree.banks.map { it.institutionId })
        val first = tree.banks.first()
        assertEquals(listOf("acct-2", "acct-1"), first.accounts.map { it.account.id })
        assertEquals(listOf("t-named"), first.accounts.single { it.account.id == "acct-1" }.transactions.map { it.id })
        assertEquals(listOf("t-loose"), first.unassigned.map { it.id })
        assertEquals("Household · Credit card · 4242", named.ledgerLabel())
        assertEquals("Account · 9999", plain.ledgerLabel())
    }

    @Test
    fun `corrections and custom categories keep stable ids and avoid the parser`() {
        val corrections = File("src/main/kotlin/expense/android/ui/ledger/LedgerEdits.kt").readText()
        assertFalse(corrections.contains("BankProfile"))
        assertFalse(corrections.contains("BankRegistry"))
        assertFalse(corrections.contains("SmsTemplate"))
        val transaction = tx("t1", "bank-1", "acct-1")
        val category = LedgerCorrections.category(
            transaction,
            "groceries",
            emptyList(),
            applyForward = true,
            correctionId = "c1",
            createdAt = Instant.parse("2026-05-02T00:00:00Z"),
        ) as UiResult.Ready
        assertEquals(transaction.dedupKey, category.value.dedupKey)
        assertEquals("groceries", category.value.updatedValue)
        val merchant = LedgerCorrections.merchant(
            transaction,
            "  Cafe  ",
            "Shop",
            applyForward = false,
            correctionId = "c2",
            createdAt = Instant.parse("2026-05-02T00:00:00Z"),
        ) as UiResult.Ready
        assertEquals("Cafe", merchant.value.updatedValue)
        assertEquals(transaction.dedupKey, merchant.value.dedupKey)
        val added = CategoryDrafts.add("Pets", "حيوانات", "pets", "shopping", emptyList()) as UiResult.Ready
        assertEquals("pets", added.value.id)
        assertEquals("pets", added.value.slug)
        assertEquals("shopping", added.value.parentId)
        val custom = Category(added.value.id, added.value.parentId, added.value.nameEn, added.value.nameAr, added.value.slug, added.value.sortOrder, system = false)
        val updated = CategoryDrafts.update(custom, "Pet care", "رعاية", "other", listOf(custom)) as UiResult.Ready
        assertEquals("pets", custom.id)
        assertEquals("Pet care", updated.value.nameEn)
        assertEquals(custom.sortOrder, updated.value.sortOrder)
        assertTrue(CategoryDrafts.update(CategorySeed.all.first(), "Food", "طعام", null, emptyList()) is UiResult.Rejected)
        assertTrue(CategoryDrafts.add("Pets", "حيوانات", "Pets", null, emptyList()) is UiResult.Rejected)
    }

    @Test
    fun `the ledger view is transaction first and names the active filter`() {
        val account = Account("acct-1", "cib", AccountKind.CREDIT_CARD, "0019", Currency.EGP)
        val state = LedgerState(
            accounts = listOf(account),
            messages = listOf(StoredSms("sms-t-named", "CIB", null, "hash", null, Instant.parse("2026-05-01T08:00:00Z"))),
            transactions = listOf(tx("t-named", "cib", "acct-1")),
        )
        val all = LedgerCards.view(state, month = null, accountId = null)
        assertEquals("All transactions", all.scopeLabel)
        val card = all.cards.single()
        assertEquals("Shop", card.title)
        assertEquals("EGP 20.00 · Debit", card.amountLine)
        assertEquals("CIB · Credit card · ••••0019", card.channelLine)
        assertEquals("1 May 2026 · 10:00", card.whenLine)
        assertEquals("Shopping", card.category)
        val month = java.time.YearMonth.of(2026, 5)
        assertEquals("May 2026", LedgerCards.scopeLabel(state, month, null))
        assertEquals(
            "May 2026 · CIB · Credit card · ••••0019",
            LedgerCards.scopeLabel(state, month, account.id),
        )
        assertTrue(LedgerCards.view(state, java.time.YearMonth.of(2026, 4), null).cards.isEmpty())
    }

    private fun tx(id: String, institutionId: String, accountId: String?): Transaction {
        return Transaction(
            id = id,
            dedupKey = "dedup-$id",
            smsId = "sms-$id",
            institutionId = institutionId,
            accountId = accountId,
            kind = TransactionKind.PURCHASE,
            status = TransactionStatus.POSTED,
            amount = Money(2000, Currency.EGP),
            direction = Direction.DEBIT,
            occurredAt = Instant.parse("2026-05-01T08:00:00Z"),
            occurredCivil = LocalDateTime.of(2026, 5, 1, 10, 0),
            occurredSource = OccurredSource.SMS_FIELD,
            merchantRaw = "Shop",
            merchantId = null,
            categoryId = "shopping",
            categorySource = CategorySource.USER,
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
        )
    }
}
