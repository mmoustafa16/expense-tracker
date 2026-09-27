package expense.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SmsManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `the app requests only read and receive sms`() {
        val permissions = Regex("""uses-permission android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            setOf(
                "android.permission.READ_SMS",
                "android.permission.RECEIVE_SMS",
            ),
            permissions,
        )
    }

    @Test
    fun `incoming sms is received through the protected sms broadcast`() {
        assertTrue(manifest.contains("""android:name=".IncomingSmsReceiver""""))
        assertTrue(manifest.contains("""android:exported="true""""))
        assertTrue(manifest.contains("""android:permission="android.permission.BROADCAST_SMS""""))
        assertTrue(manifest.contains("""android:name="android.provider.Telephony.SMS_RECEIVED""""))
        assertFalse(manifest.contains("android:priority"))
        assertFalse(manifest.contains("SMS_DELIVER"))
    }
}
