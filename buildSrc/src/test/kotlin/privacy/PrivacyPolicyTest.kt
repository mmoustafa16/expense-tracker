package privacy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkClientPolicyTest {
    @Test
    fun `network clients fail and local database drivers do not`() {
        assertTrue(NetworkClientPolicy.isForbidden("com.squareup.okhttp3", "okhttp"))
        assertTrue(NetworkClientPolicy.isForbidden("com.squareup.retrofit2", "retrofit"))
        assertTrue(NetworkClientPolicy.isForbidden("io.ktor", "ktor-client-core"))
        assertTrue(NetworkClientPolicy.isForbidden("com.android.volley", "volley"))
        assertTrue(NetworkClientPolicy.isForbidden("com.github.kittinunf.fuel", "fuel"))
        assertTrue(NetworkClientPolicy.isForbidden("org.apache.httpcomponents", "httpclient"))
        assertFalse(NetworkClientPolicy.isForbidden("app.cash.sqldelight", "android-driver"))
        assertFalse(NetworkClientPolicy.isForbidden("app.cash.sqldelight", "sqlite-driver"))
        assertFalse(NetworkClientPolicy.isForbidden("net.zetetic", "sqlcipher-android"))
        assertFalse(NetworkClientPolicy.isForbidden("androidx.sqlite", "sqlite"))
        assertFalse(NetworkClientPolicy.isForbidden("org.jetbrains.kotlin", "kotlin-stdlib"))
        assertFalse(NetworkClientPolicy.isForbidden("io.ktor", "ktor-server-core"))
        assertFalse(NetworkClientPolicy.isForbidden(null, "app"))
    }
}

class SmsLogPolicyTest {
    @Test
    fun `logging a body or amount fails`() {
        val bodyLog = "Log" + ".d(TAG, sms.body)"
        val amountLog = "println" + "(transaction.amount)"
        val minorLog = "Timber" + ".e(\"value=\" + amountMinor)"
        assertEquals(1, SmsLogPolicy.violations(bodyLog, "Body.kt").size)
        assertEquals(1, SmsLogPolicy.violations(amountLog, "Amount.kt").size)
        assertEquals(1, SmsLogPolicy.violations(minorLog, "Minor.kt").size)
    }

    @Test
    fun `hashes comments and ordinary code are allowed`() {
        val source = """
            val hash = sms.bodyHash
            val kept = message.body
            // Log.d(TAG, sms.body)
            Log.d(TAG, sms.bodyHash)
            println(messages.size)
        """.trimIndent()
        assertTrue(SmsLogPolicy.violations(source, "Safe.kt").isEmpty())
    }
}
