package expense.ledger

import expense.categories.CategorySource
import expense.money.Currency
import expense.money.Money
import expense.parse.Direction
import expense.parse.TransactionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

class SpendPolicyTest {
    @Test
    fun `spend inclusion follows the approved kinds`() {
        val included = listOf(
            TransactionKind.PURCHASE,
            TransactionKind.INSTALLMENT,
            TransactionKind.FEE,
            TransactionKind.CASH_WITHDRAWAL,
            TransactionKind.REFUND,
        )
        val excluded = listOf(
            TransactionKind.REVERSAL,
            TransactionKind.FAILED,
            TransactionKind.TRANSFER_IN,
            TransactionKind.TRANSFER_OUT,
            TransactionKind.INCOME,
            TransactionKind.UNKNOWN,
        )
        included.forEach { kind ->
            assertTrue(SpendPolicy.include(kind, TransactionStatus.POSTED), kind.name)
        }
        excluded.forEach { kind ->
            assertFalse(SpendPolicy.include(kind, TransactionStatus.POSTED), kind.name)
        }
        assertFalse(SpendPolicy.include(TransactionKind.PURCHASE, TransactionStatus.VOIDED))
    }

    @Test
    fun `refunds reduce spend and use the civil month`() {
        val refund = sample(
            kind = TransactionKind.REFUND,
            direction = Direction.CREDIT,
            include = true,
            civil = LocalDateTime.of(2026, 2, 2, 10, 0),
        )
        assertEquals(-15000L, SpendPolicy.signedMinor(refund))
        assertEquals("2026-02", SpendPolicy.spendMonth(refund).toString())
    }

    private fun sample(
        kind: TransactionKind,
        direction: Direction,
        include: Boolean,
        civil: LocalDateTime,
    ): Transaction {
        return Transaction(
            id = "t",
            dedupKey = "d",
            smsId = "s",
            institutionId = "example.test-bank",
            accountId = null,
            kind = kind,
            status = TransactionStatus.POSTED,
            amount = Money(15000, Currency.EGP),
            direction = direction,
            occurredAt = Instant.parse("2026-02-02T08:00:00Z"),
            occurredCivil = civil,
            occurredSource = OccurredSource.SMS_FIELD,
            merchantRaw = null,
            merchantId = null,
            categoryId = null,
            categorySource = CategorySource.UNCATEGORIZED,
            reference = null,
            balance = null,
            foreignAmount = null,
            duplicateOfId = null,
            linkedTransactionId = null,
            includeInSpend = include,
            pipelineVersion = "1",
            profileVersion = "1",
            installmentIndex = null,
            installmentCount = null,
        )
    }
}
