package expense.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import expense.intelligence.SemanticTransactionClassifier
import expense.intelligence.SmsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup loads [SemanticTransactionClassifier] on the Android runtime.
 * Desktop unit tests use a JDK regex engine that accepts
 * `Pattern.UNICODE_CHARACTER_CLASS`. Android throws
 * `IllegalArgumentException: UNICODE_CHARACTER_CLASS flag not supported`
 * from that flag during class initialization, which kills the process
 * before the first screen can finish opening.
 */
@RunWith(AndroidJUnit4::class)
class SemanticModelStartupTest {
    @Test
    fun bundledClassifierInitializesOnAndroid() {
        val body = "عفواً، رصيدك غير كافٍ لتجديد باقة Plus 6000. برجاء شحن 65 جنيه"
        val classification = SemanticTransactionClassifier.bundled().classify(SmsText("Vodafone", body))
        assertEquals("renewal_attempt", classification.semantics?.intent)
        assertFalse(classification.semantics!!.transactionCompleted)
        assertFalse(classification.semantics!!.moneyMovement)
        assertFalse(classification.type.isLedgerCandidate())
    }
}
