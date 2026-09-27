package expense.android.sms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmsPermissionsTest {
    @Test
    fun `only inbox read and incoming receive are required`() {
        assertEquals(
            setOf("android.permission.READ_SMS", "android.permission.RECEIVE_SMS"),
            SmsPermissions.required,
        )
        assertEquals("android.provider.Telephony.SMS_RECEIVED", SmsPermissions.SMS_RECEIVED_ACTION)
    }
}
