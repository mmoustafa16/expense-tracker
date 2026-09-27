package expense.android

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import expense.android.storage.DatabaseKeyVault
import expense.android.storage.KeyMaterial
import expense.android.storage.LedgerSession
import expense.android.storage.UnlockPrompt
import expense.android.storage.UnlockResult
import expense.android.storage.db.ExpenseDatabase
import expense.android.ui.analytics.AnalyticsSession
import expense.android.ui.analytics.AnalyticsSlice
import expense.android.ui.common.UiResult
import expense.android.ui.ledger.CategoryDrafts
import expense.android.ui.ledger.LedgerCorrections
import expense.android.ui.ledger.LedgerSessionBindings
import expense.android.ui.review.ManualEntries
import expense.android.ui.review.ReviewSession
import expense.android.ui.search.SearchSession
import expense.parse.AccountKind
import expense.parse.BankRegistry
import expense.sms.InboundSms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth

class UiSessionTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `screens call the session for review ledger search and analytics`() {
        val session = openSession(File(directory.toFile(), "ledger.db"))
        session.accept(
            listOf(
                InboundSms(
                    sender = "LAB",
                    body = "Debited EGP 20.00 for Shop",
                    providerMessageId = "1",
                    receivedAt = Instant.parse("2026-05-01T07:00:00Z"),
                ),
            ),
        )
        val open = ReviewSession.rows(session).single()
        val entry = ManualEntries.fromReview(
            ReviewSession.load(session).attempts.single(),
            LocalDateTime.of(2026, 5, 1, 10, 0),
        ).copy(
            amountText = "20.00",
            currencyCode = "EGP",
            institutionLabel = "bank-1",
            accountKind = AccountKind.DEBIT_CARD,
            accountMask = "1111",
            categoryId = "shopping",
            merchantRaw = "Shop",
            reference = "REF-9",
            occurredCivil = LocalDateTime.of(2026, 5, 1, 10, 0),
        )
        val draft = (ManualEntries.draft(entry, emptyList()) as UiResult.Ready).value
        assertTrue(ReviewSession.post(session, draft).isEmpty())
        val posted = session.load()
        val transaction = posted.transactions.single()
        assertTrue(transaction.manual)
        assertEquals("", transaction.profileVersion)
        assertEquals("shopping", transaction.categoryId)
        assertTrue(BankRegistry.EMPTY.profiles.isEmpty())

        val renamed = LedgerSessionBindings.renameAccount(session, posted.accounts.single().id, "Daily card")
        assertEquals("Daily card", renamed.accounts.single().displayName)
        assertEquals(posted.accounts.single().id, renamed.accounts.single().id)
        assertEquals("1111", renamed.accounts.single().mask)

        val added = CategoryDrafts.add("Pets", "حيوانات", "pets", "shopping", renamed.categories) as UiResult.Ready
        val withCategory = LedgerSessionBindings.addCategory(session, added.value)
        val custom = withCategory.categories.single()
        val edited = CategoryDrafts.update(custom, "Pet care", "رعاية", "shopping", withCategory.categories) as UiResult.Ready
        val updated = LedgerSessionBindings.updateCategory(session, custom.id, edited.value)
        assertEquals("pets", updated.categories.single().id)
        assertEquals("Pet care", updated.categories.single().nameEn)

        val correction = LedgerCorrections.category(
            transaction = updated.transactions.single(),
            categoryId = "groceries",
            categories = updated.categories,
            applyForward = true,
            correctionId = "correction-1",
            createdAt = Instant.parse("2026-05-03T00:00:00Z"),
        ) as UiResult.Ready
        val corrected = LedgerSessionBindings.correct(session, correction.value)
        assertEquals("groceries", corrected.transactions.single().categoryId)
        assertEquals("", corrected.transactions.single().profileVersion)

        val hits = SearchSession.query(session, "Shop")
        assertEquals(transaction.id, hits.single().transactionId)
        assertTrue(SearchSession.query(session, "not-in-the-ledger").isEmpty())

        val report = AnalyticsSession.report(session, AnalyticsSlice(month = YearMonth.of(2026, 5)))
        assertEquals(2000L, report.totals.single().signedMinor)
        val food = report.categories.single { it.categoryId == "food" }
        assertEquals(2000L, food.totals.single().signedMinor)

        session.accept(
            listOf(
                InboundSms(
                    sender = "LAB",
                    body = "Paid EGP 5.00 somewhere",
                    providerMessageId = "2",
                    receivedAt = Instant.parse("2026-05-04T07:00:00Z"),
                ),
            ),
        )
        val waiting = ReviewSession.rows(session).single()
        assertTrue(ReviewSession.dismiss(session, waiting.attemptId).isEmpty())
        assertEquals("Paid EGP 5.00 somewhere", session.load().messages.last().body)
    }

    @Test
    fun `a clear purchase reaches the ledger and analytics without a bank template`() {
        val session = openSession(File(directory.toFile(), "purchase.db"))
        session.accept(
            listOf(
                InboundSms(
                    sender = "SHOP",
                    body = "Your card was charged EGP 120.50 at Talabat on 02/03/2026 09:15",
                    providerMessageId = "c1",
                    receivedAt = Instant.parse("2026-03-02T07:15:00Z"),
                ),
            ),
        )
        val posted = session.load()
        val transaction = posted.transactions.single()
        assertEquals(false, transaction.manual)
        assertEquals("Talabat", transaction.merchantRaw)
        assertTrue(posted.reviewQueue().isEmpty())
        val report = AnalyticsSession.report(session, AnalyticsSlice(month = YearMonth.of(2026, 3)))
        assertEquals(12050L, report.totals.single().signedMinor)
    }

    private fun openSession(database: File): LedgerSession {
        val session = LedgerSession(
            databaseFile = database,
            vault = DatabaseKeyVault { KeyMaterial.Available(ByteArray(32) { 7 }) },
            openDriver = { file, _ ->
                file.parentFile?.mkdirs()
                val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
                ExpenseDatabase.Schema.create(driver)
                driver
            },
        )
        var result: UnlockResult = UnlockResult.AuthenticationFailed
        session.unlock(UnlockPrompt { success, _ -> success() }) { result = it }
        check(result == UnlockResult.Ready)
        return session
    }
}
