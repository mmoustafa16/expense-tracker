package expense.parse

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FinancialSignalTest {
    @Test
    fun `requires both a currency token and a digit`() {
        assertTrue(FinancialSignal.present("Charged EGP 20.00 at Shop"))
        assertTrue(FinancialSignal.present("تم خصم ١٥٠ جنيه"))
        assertFalse(FinancialSignal.present("See you at dinner"))
        assertFalse(FinancialSignal.present("EGP only"))
        assertFalse(FinancialSignal.present("1234"))
    }

    @Test
    fun `does not expose an amount`() {
        val present: Boolean = FinancialSignal.present("EGP 20.00")
        assertTrue(present)
    }
}
