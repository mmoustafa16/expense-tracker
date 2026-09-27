package expense.parse

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FinancialSignalTest {
    @Test
    fun `ordinary messages are not financial`() {
        assertFalse(FinancialSignal.present("See you at dinner"))
        assertFalse(FinancialSignal.present("Hello, the meeting is at 5"))
        assertFalse(FinancialSignal.present("EGP only"))
        assertFalse(FinancialSignal.present("1234"))
    }

    @Test
    fun `a currency token beside a digit is not enough`() {
        val present: Boolean = FinancialSignal.present("EGP 20.00")
        assertFalse(present)
        assertFalse(FinancialSignal.present("Call me on 0100 about the EGP rate"))
    }

    @Test
    fun `one time passwords are excluded even when they mention a payment`() {
        assertFalse(FinancialSignal.present("Your OTP is 482193"))
        assertFalse(FinancialSignal.present("OTP 482193 to confirm payment of EGP 20"))
        assertFalse(FinancialSignal.present("Your one time code is 1234 for EGP 20.00"))
        assertFalse(FinancialSignal.present("رمز التحقق 482193 لعملية دفع 20 جنيه"))
    }

    @Test
    fun `promotions and greetings are excluded`() {
        assertFalse(FinancialSignal.present("Save EGP 50 this weekend. Use code 20"))
        assertFalse(FinancialSignal.present("خصم 20% على كل المشتريات"))
        assertFalse(FinancialSignal.present("خصم يصل إلى 50 جنيه"))
        assertFalse(FinancialSignal.present("Happy birthday"))
    }

    @Test
    fun `bank notices without a completed movement are excluded`() {
        assertFalse(FinancialSignal.present("Your available balance is EGP 1,250.00"))
        assertFalse(FinancialSignal.present("Your statement is ready for account 1234"))
        assertFalse(FinancialSignal.present("Payment due EGP 500"))
        assertFalse(FinancialSignal.present("Your card ending 4242 was activated"))
        assertFalse(FinancialSignal.present("Payment reminder: EGP 80 is due"))
    }

    @Test
    fun `a probable completed movement stays financial without exposing an amount`() {
        assertTrue(FinancialSignal.present("Charged EGP 20.00 at Shop"))
        assertTrue(FinancialSignal.present("تم خصم ١٥٠ جنيه"))
        assertTrue(FinancialSignal.present("Purchase of USD 10.00 at Store"))
        assertTrue(FinancialSignal.present("Cash withdrawal EGP 500"))
        assertTrue(FinancialSignal.present("Charged EGP 40.00 at Shop. Available balance EGP 900.00"))
        assertTrue(FinancialSignal.present("You were credited EGP 10.00"))
    }
}
