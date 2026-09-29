package expense.android.ui.search

import expense.android.storage.SearchField
import expense.android.storage.SearchMatch
import expense.categories.CategorySource
import expense.ledger.LedgerState
import expense.ledger.OccurredSource
import expense.ledger.StoredSms
import expense.ledger.Transaction
import expense.ledger.TransactionStatus
import expense.merchants.Merchant
import expense.money.Currency
import expense.money.Money
import expense.parse.Direction
import expense.parse.FinancialEventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

class SearchPresentationTest {
    @Test
    fun `labels are joined onto search hits and extra ledger rows are not added`() {
        val body = "Charged EGP 20.00 at Shop"
        val state = LedgerState(
            messages = listOf(
                StoredSms("sms-1", "LAB", body, "hash", "11", Instant.parse("2026-05-01T07:00:00Z")),
                StoredSms("sms-2", "OTHER", "different text", "hash-2", "12", Instant.parse("2026-05-02T07:00:00Z")),
            ),
            merchants = listOf(Merchant("m1", "Shop", "shop")),
            transactions = listOf(transaction("tx-1", "sms-1"), transaction("tx-2", "sms-2")),
        )
        val shown = SearchPresentation.present(
            state,
            listOf(SearchMatch("tx-1", "sms-1", setOf(SearchField.MERCHANT, SearchField.BODY))),
        )
        assertEquals(1, shown.size)
        assertEquals("Shop", shown.single().title)
        assertTrue(shown.single().subtitle.contains("20.00 EGP"))
        assertTrue(shown.single().subtitle.contains("Shopping"))
        assertTrue(shown.single().subtitle.contains(body))
        assertTrue(SearchPresentation.present(state, emptyList()).isEmpty())
    }

    private fun transaction(id: String, smsId: String): Transaction {
        return Transaction(
            id = id,
            dedupKey = "dedup-$id",
            smsId = smsId,
            institutionId = "bank-1",
            accountId = null,
            eventType = FinancialEventType.CARD_PURCHASE,
            spendEffect = FinancialEventType.CARD_PURCHASE.defaultSpendEffect(),
            status = TransactionStatus.POSTED,
            amount = Money(2000, Currency.EGP),
            direction = Direction.DEBIT,
            occurredAt = Instant.parse("2026-05-01T08:00:00Z"),
            occurredCivil = LocalDateTime.of(2026, 5, 1, 10, 0),
            occurredSource = OccurredSource.SMS_FIELD,
            merchantRaw = "Shop",
            merchantId = "m1",
            categoryId = "shopping",
            categorySource = CategorySource.USER,
            reference = "REF-9",
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
