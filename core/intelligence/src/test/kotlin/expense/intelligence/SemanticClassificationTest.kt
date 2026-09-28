package expense.intelligence

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Held-out paraphrases. These strings are not the training sentences.
 * Each class is written more than once, with different wording, so a shared
 * keyword is not what the assertion is checking.
 */
class SemanticClassificationTest {
    private val classifier = SemanticTransactionClassifier.bundled()

    @Test
    fun `a renewal attempt and a completed purchase that share an amount are different`() {
        val attemptBody = "Sorry, there is not enough balance to renew the Plus 6000 package. Please add EGP 65."
        val purchaseBody = "Your card was used to purchase EGP 65 at XYZ."
        val attempt = classifier.classify(SmsText("VODAFONE", attemptBody))
        val purchase = classifier.classify(SmsText("SOME BANK", purchaseBody))

        assertEquals("renewal_attempt", attempt.semantics?.intent)
        assertFalse(attempt.semantics!!.transactionCompleted)
        assertFalse(attempt.semantics!!.moneyMovement)
        assertFalse(attempt.type.isLedgerCandidate())

        assertEquals("card_purchase", purchase.semantics?.intent)
        assertTrue(purchase.semantics!!.transactionCompleted)
        assertTrue(purchase.semantics!!.moneyMovement)
        assertEquals(MoneyDirection.DEBIT, purchase.semantics!!.direction)
        assertEquals(TransactionClass.CARD_PURCHASE, purchase.type)
        assertTrue(purchase.type.isLedgerCandidate())
        assertTrue(purchase.confidence >= 80)

        assertEquals(attempt, classifier.classify(SmsText("UNRELATED", attemptBody)))
        assertEquals(purchase, classifier.classify(SmsText("ANOTHER", purchaseBody)))
    }

    @Test
    fun `an arabic vodafone renewal attempt is not a completed money movement`() {
        val body = "عفواً، رصيدك غير كافٍ لتجديد باقة Plus 6000. برجاء شحن 65 جنيه"
        val fromCarrier = classifier.classify(SmsText("Vodafone", body))
        val fromUnknown = classifier.classify(SmsText("UNKNOWN", body))
        assertEquals(fromCarrier, fromUnknown)
        assertEquals("renewal_attempt", fromCarrier.semantics?.intent)
        assertFalse(fromCarrier.semantics!!.transactionCompleted)
        assertFalse(fromCarrier.semantics!!.moneyMovement)
        assertFalse(fromCarrier.type.isLedgerCandidate())
        assertEquals(TransactionClass.OTHER_NON_TRANSACTION, fromCarrier.type)
    }

    @Test
    fun `held-out paraphrases follow intent rather than one shared phrase`() {
        val fixtures = JSONArray(read("semantic-fixtures.json"))
        val byIntent = linkedMapOf<String, MutableList<String>>()
        for (index in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(index)
            val text = fixture.getString("text")
            byIntent.getOrPut(fixture.getString("name")) { mutableListOf() }.add(text)
            val fromBank = classifier.classify(SmsText("BANK-" + index, text))
            val fromPerson = classifier.classify(SmsText("person", text))
            assertEquals(fromBank, fromPerson, text)
            assertSemantics(fixture, fromBank)
        }
        byIntent.values.forEach { texts ->
            assertTrue(texts.size >= 2, texts.toString())
            assertTrue(texts.distinct().size == texts.size)
        }
    }

    @Test
    fun `tokenizer ids and the embedding probe match the exported model`() {
        val golden = JSONArray(read("tokenizer-golden.json"))
        val encoder = SemanticTransactionClassifier.bundledEncoder()
        for (index in 0 until golden.length()) {
            val row = golden.getJSONObject(index)
            val expected = row.getJSONArray("ids")
            val actual = encoder.tokenIds(row.getString("text"))
            assertEquals(expected.length(), actual.size, row.getString("text"))
            for (tokenIndex in 0 until expected.length()) {
                assertEquals(expected.getInt(tokenIndex), actual[tokenIndex], row.getString("text"))
            }
        }
        val manifest = JSONObject(
            SemanticTransactionClassifier.resourceBytes("manifest.json").toString(Charsets.UTF_8),
        )
        val probe = encoder.tokenVector(manifest.getString("probeToken"))
        val expected = manifest.getJSONArray("probeValues")
        for (index in 0 until expected.length()) {
            assertTrue(abs(probe[index] - expected.getDouble(index).toFloat()) < 1e-4)
        }
    }

    private fun assertSemantics(fixture: JSONObject, classification: Classification) {
        val semantics = classification.semantics
        val text = fixture.getString("text")
        assertEquals(fixture.getString("name"), semantics?.intent, text)
        assertEquals(TransactionClass.valueOf(fixture.getString("transactionClass")), classification.type, text)
        assertEquals(fixture.getBoolean("transactionCompleted"), semantics?.transactionCompleted, text)
        assertEquals(fixture.getBoolean("moneyMovement"), semantics?.moneyMovement, text)
        assertEquals(fixture.getBoolean("ambiguous"), classification.ambiguous, text)
        val direction = if (fixture.isNull("direction")) null else MoneyDirection.valueOf(fixture.getString("direction"))
        assertEquals(direction, semantics?.direction, text)
        if (classification.type.isLedgerCandidate() && !classification.ambiguous) {
            assertTrue(classification.confidence >= 80, text)
        }
    }

    private fun read(name: String): String {
        val stream = javaClass.getResourceAsStream("/expense/intelligence/$name")
            ?: error("Missing fixture $name")
        return stream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }
}
