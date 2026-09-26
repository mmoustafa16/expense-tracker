package expense.sms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class BodyHashTest {
    @Test
    fun `hash is stable and sensitive to the body`() {
        assertEquals(BodyHash.sha256("EGP 10"), BodyHash.sha256("EGP 10"))
        assertNotEquals(BodyHash.sha256("EGP 10"), BodyHash.sha256("EGP 11"))
    }
}
