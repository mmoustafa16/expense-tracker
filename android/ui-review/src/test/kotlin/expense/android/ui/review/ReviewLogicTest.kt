package expense.android.ui.review

import expense.android.ui.common.UiResult
import expense.ledger.LedgerState
import expense.ledger.ReviewDismissals
import expense.ledger.StoredSms
import expense.money.Currency
import expense.money.Money
import expense.parse.AccountKind
import expense.parse.Direction
import expense.parse.Extraction
import expense.parse.ParseAttempt
import expense.parse.ParseStatus
import expense.parse.TransactionCandidate
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

class ReviewLogicTest {
    @Test
    fun `the queue is the open review items and keeps the message body`() {
        val body = "Charged EGP 20.00 at Shop"
        val state = reviewState(body)
        val row = ReviewQueue.rows(state).single()
        assertEquals("attempt-1", row.attemptId)
        assertEquals(body, row.body)
        assertEquals(ParseStatus.UNSUPPORTED, row.status)
        val dismissed = ReviewDismissals.dismiss(state, "attempt-1")
        assertTrue(ReviewQueue.rows(dismissed).isEmpty())
        assertEquals(body, dismissed.messages.single().body)
    }

    @Test
    fun `a manual draft can omit an account and never describes a bank profile`() {
        val source = File("src/main/kotlin/expense/android/ui/review/ManualEntries.kt").readText()
        assertFalse(source.contains("BankProfile"))
        assertFalse(source.contains("BankRegistry"))
        val entry = ManualEntries.fromReview(null, LocalDateTime.of(2026, 5, 1, 10, 15, 45))
        assertEquals("", entry.institutionLabel)
        assertNull(entry.accountKind)
        val rejected = ManualEntries.draft(entry.copy(amountText = "20.00"), emptyList())
        assertTrue(rejected is UiResult.Rejected)
        val partial = entry.copy(
            amountText = "20.00",
            institutionLabel = "bank-1",
            accountKind = AccountKind.DEBIT_CARD,
        )
        assertTrue(ManualEntries.draft(partial, emptyList()) is UiResult.Rejected)
        val ready = ManualEntries.draft(
            entry.copy(amountText = "20.00", institutionLabel = "  bank-1  ", categoryId = "shopping"),
            emptyList(),
        )
        val draft = (ready as UiResult.Ready).value
        assertEquals("bank-1", draft.institutionId)
        assertNull(draft.accountKind)
        assertNull(draft.accountMask)
        assertEquals("shopping", draft.categoryId)
        assertEquals("1", draft.pipelineVersion)
    }

    @Test
    fun `review extraction can prefill amount fields and still requires a bank label`() {
        val attempt = ParseAttempt(
            id = "attempt-1",
            smsId = "sms-1",
            pipelineVersion = "1",
            profileId = null,
            profileVersion = null,
            templateId = null,
            status = ParseStatus.LOW_CONFIDENCE,
            confidence = 40,
            extraction = Extraction(
                confidence = 40,
                candidates = listOf(
                    TransactionCandidate(
                        kind = TransactionKind.PURCHASE,
                        amount = Money(2000, Currency.EGP),
                        direction = Direction.DEBIT,
                        merchantRaw = "Shop",
                        occurredAt = LocalDateTime.of(2026, 5, 1, 10, 0),
                        reference = "REF-9",
                        accountMask = "4242",
                        accountKind = AccountKind.DEBIT_CARD,
                    ),
                ),
            ),
            error = null,
        )
        val entry = ManualEntries.fromReview(attempt, LocalDateTime.of(2026, 6, 1, 0, 0))
        assertEquals("20.00", entry.amountText)
        assertEquals("", entry.institutionLabel)
        assertEquals(AccountKind.DEBIT_CARD, entry.accountKind)
        assertEquals("4242", entry.accountMask)
        val draft = ManualEntries.draft(entry.copy(institutionLabel = "bank-1"), emptyList()) as UiResult.Ready
        assertEquals(AccountKind.DEBIT_CARD, draft.value.accountKind)
        assertEquals("4242", draft.value.accountMask)
        assertEquals("attempt-1", draft.value.reviewAttemptId)
        assertTrue(ManualEntries.draft(entry.copy(institutionLabel = "bank-1", categoryId = "missing"), emptyList()) is UiResult.Rejected)
    }

    private fun reviewState(body: String): LedgerState {
        val receivedAt = Instant.parse("2026-05-01T07:00:00Z")
        return LedgerState(
            messages = listOf(
                StoredSms("sms-1", "LAB", body, BodyHash.sha256(body), "11", receivedAt),
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
