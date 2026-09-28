package expense.intelligence

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On-device semantic classifier.
 *
 * The encoder is the static embedding model `minishlab/potion-base-8M`,
 * distilled from `BAAI/bge-base-en-v1.5`. Every token in the SMS is looked up,
 * mean-pooled, and L2-normalized. A small local network then maps that one
 * vector to an intent. The sender is ignored. Weights are loaded from the
 * app resources. There is no network call and the body is not logged.
 *
 * Replace the resource files to retrain or swap the model. [TransactionClassifier]
 * and the rest of the pipeline stay the same.
 */
class SemanticTransactionClassifier private constructor(
    private val encoder: SemanticEncoder,
    private val head: SemanticHead,
) : TransactionClassifier {
    override fun classify(message: SmsText): Classification {
        return head.predict(encoder.embed(message.body))
    }

    companion object {
        fun bundled(): SemanticTransactionClassifier = Holder.classifier

        internal fun bundledEncoder(): SemanticEncoder = Holder.encoder

        private object Holder {
            val manifest: JSONObject = JSONObject(resourceText("manifest.json"))
            val encoder: SemanticEncoder = SemanticEncoder.load(manifest)
            val classifier: SemanticTransactionClassifier = SemanticTransactionClassifier(
                encoder,
                SemanticHead.load(resourceText("head.json"), manifest.getDouble("temperature")),
            )
        }

        private fun resourceText(name: String): String {
            return resourceBytes(name).toString(Charsets.UTF_8)
        }

        internal fun resourceBytes(name: String): ByteArray {
            val stream = SemanticTransactionClassifier::class.java.getResourceAsStream(
                "/expense/intelligence/semantics/$name",
            ) ?: error("Missing on-device semantic model resource $name")
            return stream.use { it.readBytes() }
        }
    }
}

internal class SemanticEncoder private constructor(
    private val vocab: Map<String, Int>,
    private val embeddings: ByteArray,
    private val dim: Int,
    private val unkId: Int,
    private val maxTokens: Int,
    private val medianTokenLength: Int,
) {
    fun embed(text: String): FloatArray {
        val ids = tokenIds(text)
        if (ids.isEmpty()) return FloatArray(dim)
        val sum = DoubleArray(dim)
        val row = FloatArray(dim)
        for (id in ids) {
            readRow(id, row)
            for (index in 0 until dim) sum[index] += row[index].toDouble()
        }
        val count = ids.size.toDouble()
        val vector = FloatArray(dim) { (sum[it] / count).toFloat() }
        normalize(vector)
        return vector
    }

    fun tokenIds(text: String): List<Int> {
        val clipped = text.take(maxTokens * medianTokenLength)
        val ids = ArrayList<Int>()
        for (word in basicTokens(clipped)) {
            ids.addAll(wordPiece(word))
        }
        return ids.asSequence().filter { it != unkId }.take(maxTokens).toList()
    }

    fun tokenVector(token: String): FloatArray {
        val id = vocab[token] ?: return FloatArray(dim)
        val row = FloatArray(dim)
        readRow(id, row)
        return row
    }

    private fun readRow(id: Int, into: FloatArray) {
        var offset = id * dim * 2
        for (index in 0 until dim) {
            val low = embeddings[offset].toInt() and 0xFF
            val high = embeddings[offset + 1].toInt() and 0xFF
            into[index] = float16ToFloat(low or (high shl 8))
            offset += 2
        }
    }

    private fun wordPiece(word: String): List<Int> {
        if (word.length > MAX_WORD_CHARS) return listOf(unkId)
        val chars = word.toCharArray()
        var start = 0
        val ids = ArrayList<Int>()
        while (start < chars.size) {
            var end = chars.size
            var found: Int? = null
            while (start < end) {
                val piece = String(chars, start, end - start)
                val token = if (start > 0) "##$piece" else piece
                val id = vocab[token]
                if (id != null) {
                    found = id
                    break
                }
                end -= 1
            }
            if (found == null) return listOf(unkId)
            ids.add(found)
            start = end
        }
        return ids
    }

    private fun basicTokens(text: String): List<String> {
        val pieces = mutableListOf<Any>()
        for (word in normalize(text).split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            var fresh = true
            var bucket = StringBuilder()
            for (char in word) {
                if (isPunctuation(char)) {
                    pieces.add(char.toString())
                    fresh = true
                } else {
                    if (fresh) {
                        bucket = StringBuilder()
                        pieces.add(bucket)
                        fresh = false
                    }
                    bucket.append(char)
                }
            }
        }
        return pieces.mapNotNull { piece ->
            when (piece) {
                is StringBuilder -> piece.toString().ifEmpty { null }
                is String -> piece
                else -> null
            }
        }
    }

    companion object {
        private const val MAX_WORD_CHARS: Int = 100

        fun load(manifest: JSONObject): SemanticEncoder {
            val tokens = JSONArray(SemanticTransactionClassifier.resourceBytes("vocab.json").toString(Charsets.UTF_8))
            val vocab = HashMap<String, Int>(tokens.length())
            for (index in 0 until tokens.length()) {
                vocab[tokens.getString(index)] = index
            }
            return SemanticEncoder(
                vocab = vocab,
                embeddings = SemanticTransactionClassifier.resourceBytes("embeddings.f16"),
                dim = manifest.getInt("dim"),
                unkId = manifest.getInt("unkId"),
                maxTokens = manifest.getInt("maxTokens"),
                medianTokenLength = manifest.getInt("medianTokenLength"),
            )
        }
    }
}

internal class SemanticHead private constructor(
    private val temperature: Double,
    private val labels: List<SemanticLabel>,
    private val hiddenWeights: Array<FloatArray>,
    private val hiddenBias: FloatArray,
    private val outputWeights: Array<FloatArray>,
    private val outputBias: FloatArray,
) {
    fun predict(vector: FloatArray): Classification {
        val hidden = FloatArray(hiddenBias.size)
        for (hiddenIndex in hidden.indices) {
            var sum = hiddenBias[hiddenIndex].toDouble()
            for (dim in vector.indices) {
                sum += vector[dim].toDouble() * hiddenWeights[dim][hiddenIndex].toDouble()
            }
            hidden[hiddenIndex] = if (sum > 0.0) sum.toFloat() else 0f
        }
        val logits = DoubleArray(outputBias.size)
        var maxLogit = Double.NEGATIVE_INFINITY
        for (classIndex in logits.indices) {
            var sum = outputBias[classIndex].toDouble()
            for (hiddenIndex in hidden.indices) {
                sum += hidden[hiddenIndex].toDouble() * outputWeights[hiddenIndex][classIndex].toDouble()
            }
            val scaled = sum / temperature
            logits[classIndex] = scaled
            if (scaled > maxLogit) maxLogit = scaled
        }
        val probabilities = DoubleArray(logits.size)
        var total = 0.0
        for (classIndex in logits.indices) {
            val weight = exp(logits[classIndex] - maxLogit)
            probabilities[classIndex] = weight
            total += weight
        }
        var best = 0
        for (classIndex in probabilities.indices) {
            probabilities[classIndex] /= total
            if (probabilities[classIndex] > probabilities[best]) best = classIndex
        }
        val label = labels[best]
        val confidence = (probabilities[best] * 100.0).roundToInt().coerceIn(0, 100)
        val semantics = SmsSemantics(
            intent = label.name,
            transactionCompleted = label.transactionCompleted,
            moneyMovement = label.moneyMovement,
            direction = label.direction,
            confidence = confidence,
        )
        return Classification(
            type = label.type,
            confidence = confidence,
            ambiguous = label.ambiguous,
            semantics = semantics,
        )
    }

    companion object {
        fun load(json: String, temperature: Double): SemanticHead {
            val head = JSONObject(json)
            val labelsJson = head.getJSONArray("labels")
            val labels = ArrayList<SemanticLabel>(labelsJson.length())
            for (index in 0 until labelsJson.length()) {
                val row = labelsJson.getJSONObject(index)
                labels.add(
                    SemanticLabel(
                        name = row.getString("name"),
                        type = TransactionClass.valueOf(row.getString("transactionClass")),
                        transactionCompleted = row.getBoolean("transactionCompleted"),
                        moneyMovement = row.getBoolean("moneyMovement"),
                        direction = if (row.isNull("direction")) {
                            null
                        } else {
                            MoneyDirection.valueOf(row.getString("direction"))
                        },
                        ambiguous = row.getBoolean("ambiguous"),
                    ),
                )
            }
            return SemanticHead(
                temperature = temperature,
                labels = labels,
                hiddenWeights = head.getJSONArray("hiddenWeights").toMatrix(),
                hiddenBias = head.getJSONArray("hiddenBias").toVector(),
                outputWeights = head.getJSONArray("outputWeights").toMatrix(),
                outputBias = head.getJSONArray("outputBias").toVector(),
            )
        }
    }
}

private data class SemanticLabel(
    val name: String,
    val type: TransactionClass,
    val transactionCompleted: Boolean,
    val moneyMovement: Boolean,
    val direction: MoneyDirection?,
    val ambiguous: Boolean,
)

private fun JSONArray.toMatrix(): Array<FloatArray> {
    return Array(length()) { rowIndex ->
        getJSONArray(rowIndex).toVector()
    }
}

private fun JSONArray.toVector(): FloatArray {
    return FloatArray(length()) { index -> getDouble(index).toFloat() }
}

private fun normalize(vector: FloatArray) {
    var sum = 0.0
    for (value in vector) sum += value.toDouble() * value.toDouble()
    val norm = sqrt(sum) + 1e-32
    for (index in vector.indices) {
        vector[index] = (vector[index] / norm).toFloat()
    }
}

internal fun float16ToFloat(bits: Int): Float {
    val sign = bits and 0x8000
    val exponent = (bits ushr 10) and 0x1F
    val fraction = bits and 0x3FF
    val floatBits = when (exponent) {
        0 -> if (fraction == 0) {
            sign shl 16
        } else {
            var magnitude = fraction
            var shift = -1
            while (magnitude and 0x400 == 0) {
                magnitude = magnitude shl 1
                shift -= 1
            }
            val mantissa = magnitude and 0x3FF
            (sign shl 16) or ((shift + 127) shl 23) or (mantissa shl 13)
        }
        0x1F -> (sign shl 16) or (0xFF shl 23) or (fraction shl 13)
        else -> (sign shl 16) or ((exponent + 112) shl 23) or (fraction shl 13)
    }
    return Float.fromBits(floatBits)
}

private fun normalize(text: String): String {
    val cleaned = StringBuilder()
    for (char in text) {
        val code = char.code
        if (code == 0 || code == 0xFFFD || isControl(char)) continue
        cleaned.append(if (isWhitespace(char)) ' ' else char)
    }
    val spaced = StringBuilder()
    for (char in cleaned.toString()) {
        if (isChinese(char.code)) {
            spaced.append(' ').append(char).append(' ')
        } else {
            spaced.append(char)
        }
    }
    val stripped = Normalizer.normalize(spaced, Normalizer.Form.NFD)
    val withoutMarks = StringBuilder()
    for (char in stripped) {
        if (Character.getType(char) != Character.NON_SPACING_MARK.toInt()) {
            withoutMarks.append(char)
        }
    }
    return withoutMarks.toString().lowercase(Locale.ROOT)
}

private fun isControl(char: Char): Boolean {
    if (char == '\t' || char == '\n' || char == '\r') return false
    return when (Character.getType(char)) {
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.SURROGATE.toInt(),
        Character.PRIVATE_USE.toInt(),
        Character.UNASSIGNED.toInt(),
        -> true
        else -> false
    }
}

private fun isWhitespace(char: Char): Boolean {
    if (char == ' ' || char == '\t' || char == '\n' || char == '\r') return true
    return Character.getType(char) == Character.SPACE_SEPARATOR.toInt()
}

private fun isPunctuation(char: Char): Boolean {
    val code = char.code
    if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
    return when (Character.getType(char)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        -> true
        else -> false
    }
}

private fun isChinese(code: Int): Boolean {
    return code in 0x4E00..0x9FFF ||
        code in 0x3400..0x4DBF ||
        code in 0x20000..0x2A6DF ||
        code in 0x2A700..0x2B73F ||
        code in 0x2B740..0x2B81F ||
        code in 0x2B820..0x2CEAF ||
        code in 0xF900..0xFAFF ||
        code in 0x2F800..0x2FA1F
}
