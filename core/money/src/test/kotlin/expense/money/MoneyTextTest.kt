package expense.money

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MoneyTextTest {
    @Test
    fun `parses grouped western and european amounts in piastres`() {
        assertEquals(15000L, MoneyText.parse("EGP 150.00", Currency.EGP)?.amountMinor)
        assertEquals(15050L, MoneyText.parse("150,50", Currency.EGP)?.amountMinor)
        assertEquals(123450L, MoneyText.parse("1,234.50", Currency.EGP)?.amountMinor)
        assertEquals(123450L, MoneyText.parse("1.234,50", Currency.EGP)?.amountMinor)
        assertEquals(123400L, MoneyText.parse("1,234", Currency.EGP)?.amountMinor)
        assertEquals(15050L, MoneyText.parse("150.5", Currency.EGP)?.amountMinor)
    }

    @Test
    fun `folds arabic indic and eastern arabic indic digits`() {
        assertEquals(15050L, MoneyText.parse("١٥٠٫٥٠", Currency.EGP)?.amountMinor)
        assertEquals(12025L, MoneyText.parse("۱۲۰٫۲۵", Currency.EGP)?.amountMinor)
        assertEquals("150.50", DigitFold.fold("١٥٠٫٥٠"))
        assertEquals("120.25", DigitFold.fold("۱۲۰٫۲۵"))
    }

    @Test
    fun `rejects extra fraction digits instead of rounding`() {
        assertNull(MoneyText.parse("12.3456", Currency.EGP))
    }

    @Test
    fun `reads the absolute number and leaves sign to direction`() {
        assertEquals(Money(500, Currency.EGP), MoneyText.parse("-5.00", Currency.EGP))
    }

    @Test
    fun `egp uses two minor units`() {
        assertEquals(2, Currency.EGP.minorUnits)
        assertEquals(Currency.EGP, Currency.of("egp"))
    }
}
