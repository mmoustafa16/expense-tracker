package expense.intelligence

import expense.parse.Direction
import expense.parse.FinancialEventType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.regex.Pattern
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
        assertEquals(FinancialEventType.PAYMENT_DUE, attempt.eventType)
        assertFalse(attempt.eventType.canMoveMoney())

        assertEquals("card_purchase", purchase.semantics?.intent)
        assertEquals(Direction.DEBIT, purchase.semantics!!.direction)
        assertEquals(FinancialEventType.CARD_PURCHASE, purchase.eventType)
        assertTrue(purchase.eventType.canMoveMoney())
        assertTrue(purchase.confidence >= 80)

        assertEquals(attempt, classifier.classify(SmsText("UNRELATED", attemptBody)))
        assertEquals(purchase, classifier.classify(SmsText("ANOTHER", purchaseBody)))
    }

    @Test
    fun `normalizer patterns avoid the regex flag Android rejects at startup`() {
        val compiled = compileNormalizerPattern("\\s+")
        assertEquals(0, compiled.flags() and Pattern.UNICODE_CHARACTER_CLASS)
        val reference = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS)
        for (code in 0..0xFFFF) {
            val text = code.toChar().toString()
            assertEquals(
                reference.matcher(text).matches(),
                compiled.matcher(text).matches(),
                "U+%04X".format(code),
            )
        }
        assertTrue(compiled.matcher("\u00A0\u3000").matches())
        assertFalse(compiled.matcher("ع").matches())
    }

    @Test
    fun `a completed account debit for a transfer is not a balance notice`() {
        val transfer = classifier.classify(
            SmsText(
                "CIB",
                "Your account ending with ******9438 is debited with amount EGP 31.89DR on 31 MAR 2024 with transfer to another account.",
            ),
        )
        assertEquals("transfer_out", transfer.semantics?.intent)
        assertEquals(Direction.DEBIT, transfer.semantics!!.direction)
        assertEquals(FinancialEventType.BANK_TRANSFER, transfer.eventType)
        assertTrue(transfer.confidence >= 80)

        val paraphrases = listOf(
            "The account was debited because a transfer to another account completed on 31 March.",
            "Funds left the account through a completed transfer. The debit has posted.",
            "A completed transfer to another account debited the account. Available balance afterwards is EGP 640.00.",
            "Your account was debited EGP 75.00 for a transfer to another account. Available limit remains EGP 4,000.00.",
            "خُصم من حسابك مبلغ لأن تحويلاً مكتملاً خرج إلى حساب آخر.",
        )
        paraphrases.forEach { body ->
            val classified = classifier.classify(SmsText("UNKNOWN", body))
            assertEquals("transfer_out", classified.semantics?.intent, body)
            assertEquals(Direction.DEBIT, classified.semantics!!.direction, body)
            assertEquals(FinancialEventType.BANK_TRANSFER, classified.eventType, body)
            assertTrue(classified.confidence >= 80, body)
        }

        val credit = classifier.classify(
            SmsText(
                "CIB",
                "Your account was credited with amount EGP 120.00CR on 02 APR 2024 from a transfer by another account.",
            ),
        )
        assertEquals("transfer_in", credit.semantics?.intent)
        assertEquals(Direction.CREDIT, credit.semantics!!.direction)
        assertEquals(FinancialEventType.BANK_TRANSFER, credit.eventType)

        val purchase = classifier.classify(
            SmsText(
                "CIB",
                "Your account ending with ****2219 is debited with amount EGP 54.00DR on 04 APR 2024 for a purchase at the market.",
            ),
        )
        assertEquals("card_purchase", purchase.semantics?.intent)
        assertEquals(Direction.DEBIT, purchase.semantics!!.direction)
        assertEquals(FinancialEventType.CARD_PURCHASE, purchase.eventType)

        val balance = classifier.classify(
            SmsText(
                "CIB",
                "Your account ending with ****1008 has available balance EGP 500.00. No transfer or debit took place.",
            ),
        )
        assertEquals("balance", balance.semantics?.intent)
        assertEquals(FinancialEventType.BALANCE_NOTIFICATION, balance.eventType)
        assertFalse(balance.eventType.canMoveMoney())
    }

    @Test
    fun `an arabic vodafone renewal attempt is not a completed money movement`() {
        val body = "عفواً، رصيدك غير كافٍ لتجديد باقة Plus 6000. برجاء شحن 65 جنيه"
        val fromCarrier = classifier.classify(SmsText("Vodafone", body))
        val fromUnknown = classifier.classify(SmsText("UNKNOWN", body))
        assertEquals(fromCarrier, fromUnknown)
        assertEquals("renewal_attempt", fromCarrier.semantics?.intent)
        assertEquals(FinancialEventType.PAYMENT_DUE, fromCarrier.eventType)
        assertFalse(fromCarrier.eventType.canMoveMoney())
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
        assertEquals(FinancialEventType.valueOf(fixture.getString("eventType")), classification.eventType, text)
        assertEquals(fixture.getBoolean("ambiguous"), classification.ambiguous, text)
        val direction = if (fixture.isNull("direction")) null else Direction.valueOf(fixture.getString("direction"))
        assertEquals(direction, semantics?.direction, text)
        if (classification.eventType.canMoveMoney() && !classification.ambiguous) {
            assertTrue(classification.confidence >= 80, text)
        }
    }

    private fun read(name: String): String {
        val stream = javaClass.getResourceAsStream("/expense/intelligence/$name")
            ?: error("Missing fixture $name")
        return stream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }
}
