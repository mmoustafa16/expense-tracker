package expense.ledger

import expense.categories.CategorySource
import expense.money.Currency
import expense.money.Money
import expense.parse.Direction
import expense.parse.FinancialEventType
import expense.parse.SpendEffect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

class SpendPolicyTest {
    @Test
    fun `spend inclusion follows the spend effect`() {
        val included = listOf(SpendEffect.SPEND, SpendEffect.SPEND_REVERSAL)
        val excluded = listOf(
            SpendEffect.TRANSFER_INTERNAL,
            SpendEffect.TRANSFER_EXTERNAL,
            SpendEffect.LIABILITY_SETTLEMENT,
            SpendEffect.INCOME,
            SpendEffect.NONE,
        )
        included.forEach { effect ->
            assertTrue(SpendPolicy.include(effect, TransactionStatus.POSTED), effect.name)
        }
        excluded.forEach { effect ->
            assertFalse(SpendPolicy.include(effect, TransactionStatus.POSTED), effect.name)
        }
        assertFalse(SpendPolicy.include(SpendEffect.SPEND, TransactionStatus.VOIDED))
    }

    @Test
    fun `paying a credit card settles a liability instead of spending again`() {
        val payment = FinancialEventType.CREDIT_CARD_PAYMENT
        assertEquals(SpendEffect.LIABILITY_SETTLEMENT, payment.defaultSpendEffect())
        assertFalse(SpendPolicy.include(payment.defaultSpendEffect(), TransactionStatus.POSTED))
        assertTrue(
            SpendPolicy.include(
                FinancialEventType.CARD_PURCHASE.defaultSpendEffect(),
                TransactionStatus.POSTED,
            ),
        )
    }

    @Test
    fun `refunds reduce spend and use the civil month`() {
        val refund = sample(
            eventType = FinancialEventType.REFUND,
            direction = Direction.CREDIT,
            include = true,
            civil = LocalDateTime.of(2026, 2, 2, 10, 0),
        )
        assertEquals(-15000L, SpendPolicy.signedMinor(refund))
        assertEquals("2026-02", SpendPolicy.spendMonth(refund).toString())
    }

    private fun sample(
        eventType: FinancialEventType,
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
            eventType = eventType,
            spendEffect = eventType.defaultSpendEffect(),
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
