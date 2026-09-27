package expense.android.ui.analytics

import expense.categories.Category
import expense.categories.CategorySource
import expense.ledger.Account
import expense.ledger.LedgerState
import expense.ledger.OccurredSource
import expense.ledger.Transaction
import expense.ledger.TransactionStatus
import expense.merchants.Merchant
import expense.money.Currency
import expense.money.Money
import expense.parse.AccountKind
import expense.parse.Direction
import expense.parse.TransactionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth

class SpendAnalyticsTest {
    @Test
    fun `parent totals include children and currencies stay separate`() {
        val pets = Category("pets", "shopping", "Pets", "حيوانات", "pets", 61, system = false)
        val state = LedgerState(
            categories = listOf(pets),
            merchants = listOf(Merchant("m-shop", "Shop", "shop"), Merchant("m-vet", "Vet", "vet")),
            accounts = listOf(Account("acct-1", "bank-1", AccountKind.DEBIT_CARD, "4242", Currency.EGP, "Daily")),
            transactions = listOf(
                tx("food-direct", "food", 2500),
                tx("groceries", "groceries", 10000),
                tx("restaurants", "restaurants", 5000),
                tx("pets", "pets", 3000),
                tx("usd", "shopping", 1000, currency = Currency.USD, merchantId = "m-shop"),
            ),
        )
        val report = SpendAnalytics.report(state, AnalyticsSlice(month = YearMonth.of(2026, 5)))
        val food = report.categories.single { it.categoryId == "food" }
        val shopping = report.categories.single { it.categoryId == "shopping" }
        assertEquals(listOf(17500L), food.totals.map { it.signedMinor })
        assertEquals(listOf(3000L, 1000L), shopping.totals.map { it.signedMinor })
        assertEquals(listOf("EGP", "USD"), shopping.totals.map { it.currency.code })
        assertEquals(listOf(20500L, 1000L), report.totals.map { it.signedMinor })
        assertEquals(5, report.transactionCount)
        val drilled = SpendAnalytics.report(state, AnalyticsSlice(categoryId = "food"))
        assertEquals(listOf(17500L), drilled.totals.map { it.signedMinor })
        assertEquals(setOf("food", "groceries", "restaurants"), drilled.categories.map { it.categoryId }.toSet())
        assertEquals(listOf("Shop"), drilled.merchants.map { it.name }.distinct())
        val childSum = drilled.categories.filter { it.categoryId != "food" }.sumOf { it.totals.single().signedMinor }
        assertTrue(drilled.totals.single().signedMinor < childSum + food.totals.single().signedMinor)
    }

    @Test
    fun `refunds reduce spend and other slices do not mix rows`() {
        val state = LedgerState(
            merchants = listOf(Merchant("m-shop", "Shop", "shop"), Merchant("m-cafe", "Cafe", "cafe")),
            accounts = listOf(
                Account("acct-1", "bank-1", AccountKind.DEBIT_CARD, "4242", Currency.EGP),
                Account("acct-2", "bank-2", AccountKind.CREDIT_CARD, "9999", Currency.EGP, "Travel"),
            ),
            transactions = listOf(
                tx("buy", "groceries", 10000, accountId = "acct-1", institutionId = "bank-1", merchantId = "m-shop"),
                tx(
                    "refund",
                    "groceries",
                    2000,
                    direction = Direction.CREDIT,
                    kind = TransactionKind.REFUND,
                    accountId = "acct-1",
                    institutionId = "bank-1",
                    merchantId = "m-shop",
                ),
                tx("other-bank", "fuel", 4000, accountId = "acct-2", institutionId = "bank-2", merchantId = "m-cafe"),
                tx("april", "groceries", 9000, month = 4, accountId = "acct-1", institutionId = "bank-1"),
                tx("transfer", "transfers", 8000, kind = TransactionKind.TRANSFER_OUT, include = false),
                tx("plain", null, 1500, merchantId = null, merchantRaw = "Corner", accountId = null),
            ),
        )
        val may = SpendAnalytics.report(state, AnalyticsSlice(month = YearMonth.of(2026, 5)))
        assertEquals(13500L, may.totals.single().signedMinor)
        assertTrue(may.categories.any { it.categoryId == SpendAnalytics.UNCATEGORIZED })
        val groceries = SpendAnalytics.report(state, AnalyticsSlice(categoryId = "groceries"))
        assertEquals(17000L, groceries.totals.single().signedMinor)
        val bank = SpendAnalytics.report(state, AnalyticsSlice(institutionId = "bank-2"))
        assertEquals(4000L, bank.totals.single().signedMinor)
        val account = SpendAnalytics.report(state, AnalyticsSlice(accountId = "acct-1"))
        assertEquals(17000L, account.totals.single().signedMinor)
        val merchant = SpendAnalytics.report(state, AnalyticsSlice(merchantId = "m-cafe"))
        assertEquals("Cafe", merchant.merchants.single().name)
        assertEquals(4000L, merchant.totals.single().signedMinor)
        val one = SpendAnalytics.report(state, AnalyticsSlice(transactionId = "buy"))
        assertEquals(1, one.transactionCount)
        assertEquals(10000L, one.totals.single().signedMinor)
        val options = SpendAnalytics.options(state)
        assertEquals(listOf(YearMonth.of(2026, 5), YearMonth.of(2026, 4)), options.months)
        assertEquals(listOf("bank-1", "bank-2"), options.institutions)
        val year = SpendAnalytics.report(state, AnalyticsSlice(year = 2026))
        assertEquals(5, year.transactionCount)
        assertTrue(AnalyticsCalendar.years(options.months) == listOf(2026))
        assertEquals(options.months, AnalyticsCalendar.monthsFor(options.months, 2026))
    }

    @Test
    fun `an empty ledger does not invent months`() {
        val options = SpendAnalytics.options(LedgerState())
        assertTrue(options.months.isEmpty())
        assertTrue(AnalyticsCalendar.years(options.months).isEmpty())
        assertTrue(AnalyticsCalendar.monthsFor(options.months, 2026).isEmpty())
        assertEquals(0, SpendAnalytics.report(LedgerState(), AnalyticsSlice(month = YearMonth.of(2026, 5))).transactionCount)
    }

    private fun tx(
        id: String,
        categoryId: String?,
        minor: Long,
        currency: Currency = Currency.EGP,
        direction: Direction = Direction.DEBIT,
        kind: TransactionKind = TransactionKind.PURCHASE,
        include: Boolean = true,
        institutionId: String = "bank-1",
        accountId: String? = "acct-1",
        merchantId: String? = "m-shop",
        merchantRaw: String? = "Shop",
        month: Int = 5,
    ): Transaction {
        val civil = LocalDateTime.of(2026, month, 2, 9, 30)
        return Transaction(
            id = id,
            dedupKey = "dedup-$id",
            smsId = "sms-$id",
            institutionId = institutionId,
            accountId = accountId,
            kind = kind,
            status = if (include) TransactionStatus.POSTED else TransactionStatus.EXCLUDED,
            amount = Money(minor, currency),
            direction = direction,
            occurredAt = Instant.parse("2026-05-02T07:30:00Z"),
            occurredCivil = civil,
            occurredSource = OccurredSource.MANUAL,
            merchantRaw = merchantRaw,
            merchantId = merchantId,
            categoryId = categoryId,
            categorySource = if (categoryId == null) CategorySource.UNCATEGORIZED else CategorySource.USER,
            reference = "ref-$id",
            balance = null,
            foreignAmount = null,
            duplicateOfId = null,
            linkedTransactionId = null,
            includeInSpend = include,
            pipelineVersion = "1",
            profileVersion = "",
            installmentIndex = null,
            installmentCount = null,
            manual = true,
        )
    }
}
