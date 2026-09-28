package expense.intelligence

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.regex.Pattern
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On-device semantic classifier.
 *
 * The encoder is a compact slice of `minishlab/potion-multilingual-128M`,
 * static embeddings distilled from `BAAI/bge-m3`. Tokenization is that model's
 * Unigram tokenizer: SentencePiece charsmap, punctuation spacing, a metaspace
 * marker, then a Viterbi search over the kept vocabulary. Tokens are
 * mean-pooled and L2-normalized. A small local network maps that vector to an
 * intent. The sender is ignored. Weights ship inside the app. There is no
 * network call and the body is not logged.
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
    private val trie: TrieNode,
    private val tokenToId: Map<String, Int>,
    private val embeddings: ByteArray,
    private val dim: Int,
    private val unkLocal: Int,
    private val unkScore: Double,
    private val maxTokens: Int,
    private val medianTokenLength: Int,
    private val charsMap: CharsMap,
    private val replacements: List<TextReplacement>,
    private val strip: Boolean,
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
        val prepared = metaspace(normalize(clipped))
        return encode(prepared).asSequence().filter { it != unkLocal }.take(maxTokens).toList()
    }

    fun tokenVector(token: String): FloatArray {
        val id = tokenToId[token] ?: return FloatArray(dim)
        val row = FloatArray(dim)
        readRow(id, row)
        return row
    }

    private fun normalize(text: String): String {
        var current = charsMap.normalize(text)
        for (step in replacements) {
            current = step.apply(current)
        }
        return if (strip) current.trim() else current
    }

    private fun metaspace(text: String): String {
        if (text.isEmpty()) return ""
        return META + text.replace(" ", META)
    }

    private fun encode(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()
        val chars = codePointsOf(text)
        val size = chars.size
        val best = DoubleArray(size + 1) { Double.NEGATIVE_INFINITY }
        best[0] = 0.0
        val backPos = IntArray(size + 1) { -1 }
        val backId = IntArray(size + 1) { -1 }
        for (start in 0 until size) {
            if (best[start] == Double.NEGATIVE_INFINITY) continue
            var node = trie
            var end = start
            while (end < size) {
                val next = node.children[chars[end]] ?: break
                node = next
                end += 1
                if (node.tokenId >= 0) {
                    val score = best[start] + node.score
                    if (score > best[end]) {
                        best[end] = score
                        backPos[end] = start
                        backId[end] = node.tokenId
                    }
                }
            }
            if (backPos[start + 1] != start && best[start + 1] < best[start] + unkScore && end == start) {
                val score = best[start] + unkScore
                if (score > best[start + 1]) {
                    best[start + 1] = score
                    backPos[start + 1] = start
                    backId[start + 1] = unkLocal
                }
            }
        }
        if (best[size] == Double.NEGATIVE_INFINITY) return listOf(unkLocal)
        var cursor = size
        val localIds = ArrayList<Int>()
        while (cursor > 0) {
            val prev = backPos[cursor]
            if (prev < 0) break
            localIds.add(backId[cursor])
            cursor = prev
        }
        localIds.reverse()
        return localIds
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

    companion object {
        private const val META: String = "\u2581"

        fun load(manifest: JSONObject): SemanticEncoder {
            val tokens = JSONArray(SemanticTransactionClassifier.resourceBytes("vocab.json").toString(Charsets.UTF_8))
            val scoreBytes = SemanticTransactionClassifier.resourceBytes("scores.f32")
            val scores = FloatArray(tokens.length())
            val scoreBuffer = ByteBuffer.wrap(scoreBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (index in scores.indices) scores[index] = scoreBuffer.float
            val trie = TrieNode()
            val tokenToId = HashMap<String, Int>(tokens.length())
            for (index in 0 until tokens.length()) {
                val token = tokens.getString(index)
                tokenToId[token] = index
                var node = trie
                var offset = 0
                while (offset < token.length) {
                    val codePoint = token.codePointAt(offset)
                    node = node.children.getOrPut(codePoint) { TrieNode() }
                    offset += Character.charCount(codePoint)
                }
                node.tokenId = index
                node.score = scores[index].toDouble()
            }
            val normalizer = JSONObject(
                SemanticTransactionClassifier.resourceBytes("normalizer.json").toString(Charsets.UTF_8),
            )
            val steps = normalizer.getJSONArray("replacements")
            val replacements = ArrayList<TextReplacement>(steps.length())
            for (index in 0 until steps.length()) {
                val step = steps.getJSONObject(index)
                val content = step.getString("content")
                replacements.add(
                    if (step.has("regex")) {
                        TextReplacement(
                            pattern = compileNormalizerPattern(step.getString("regex")),
                            literal = null,
                            content = content,
                        )
                    } else {
                        TextReplacement(pattern = null, literal = step.getString("string"), content = content)
                    },
                )
            }
            val unkLocal = manifest.getInt("unkId")
            return SemanticEncoder(
                trie = trie,
                tokenToId = tokenToId,
                embeddings = SemanticTransactionClassifier.resourceBytes("embeddings.f16"),
                dim = manifest.getInt("dim"),
                unkLocal = unkLocal,
                unkScore = scores[unkLocal].toDouble(),
                maxTokens = manifest.getInt("maxTokens"),
                medianTokenLength = manifest.getInt("medianTokenLength"),
                charsMap = CharsMap(SemanticTransactionClassifier.resourceBytes("charsmap.bin")),
                replacements = replacements,
                strip = normalizer.optBoolean("strip", true),
            )
        }
    }
}

private class TrieNode {
    val children: HashMap<Int, TrieNode> = HashMap()
    var tokenId: Int = -1
    var score: Double = 0.0
}

private class TextReplacement(
    private val pattern: Pattern?,
    private val literal: String?,
    private val content: String,
) {
    fun apply(text: String): String {
        val compiled = pattern
        if (compiled != null) {
            return compiled.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(content))
        }
        return text.replace(literal!!, content)
    }
}

/**
 * SentencePiece precompiled charsmap. Matches are byte offsets into the
 * normalized UTF-8 blob, and the longest prefix wins.
 */
internal class CharsMap(blob: ByteArray) {
    private val units: LongArray
    private val normalized: ByteArray

    init {
        val trieBytes = u32(blob, 0).toInt()
        val count = trieBytes / 4
        units = LongArray(count)
        var offset = 4
        for (index in 0 until count) {
            units[index] = u32(blob, offset)
            offset += 4
        }
        normalized = blob.copyOfRange(offset, blob.size)
    }

    fun normalize(text: String): String {
        val out = StringBuilder()
        for (cluster in graphemes(text)) {
            if (utf8Length(cluster) < 6) {
                val replaced = transform(cluster)
                if (replaced != null) {
                    out.append(replaced)
                    continue
                }
            }
            var index = 0
            for (codePoint in codePointsOf(cluster)) {
                val size = utf8Length(codePoint)
                val part = codePointSlice(cluster, index, index + size)
                index += size
                val replaced = transform(part)
                if (replaced == null) out.appendCodePoint(codePoint) else out.append(replaced)
            }
        }
        return out.toString()
    }

    private fun transform(chunk: String): String? {
        if (chunk.isEmpty()) return null
        val results = prefixes(chunk.toByteArray(Charsets.UTF_8))
        if (results.isEmpty()) return null
        val index = results.last()
        var end = index
        while (end < normalized.size && normalized[end] != 0.toByte()) end += 1
        return normalized.copyOfRange(index, end).toString(Charsets.UTF_8)
    }

    private fun prefixes(key: ByteArray): List<Int> {
        var node = 0
        var unit = units[node]
        node = node xor offset(unit)
        val found = ArrayList<Int>()
        for (raw in key) {
            val byte = raw.toInt() and 0xFF
            if (byte == 0) break
            node = node xor byte
            if (node < 0 || node >= units.size) return found
            unit = units[node]
            if (label(unit) != byte) return found
            node = node xor offset(unit)
            if (hasLeaf(unit)) {
                if (node < 0 || node >= units.size) return found
                found.add(valueOf(units[node]))
            }
        }
        return found
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

private fun graphemes(text: String): List<String> {
    val clusters = ArrayList<String>()
    val buffer = StringBuilder()
    for (codePoint in codePointsOf(text)) {
        if (buffer.isEmpty()) {
            buffer.appendCodePoint(codePoint)
            continue
        }
        if (isExtend(codePoint)) {
            buffer.appendCodePoint(codePoint)
        } else {
            clusters.add(buffer.toString())
            buffer.setLength(0)
            buffer.appendCodePoint(codePoint)
        }
    }
    if (buffer.isNotEmpty()) clusters.add(buffer.toString())
    return clusters
}

private fun isExtend(codePoint: Int): Boolean {
    return when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
    }
}

private fun codePointsOf(text: String): IntArray {
    val count = text.codePointCount(0, text.length)
    val points = IntArray(count)
    var offset = 0
    var index = 0
    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        points[index] = codePoint
        index += 1
        offset += Character.charCount(codePoint)
    }
    return points
}

private fun codePointSlice(text: String, start: Int, endExclusive: Int): String {
    val count = text.codePointCount(0, text.length)
    val from = start.coerceIn(0, count)
    val to = endExclusive.coerceIn(from, count)
    val startIndex = text.offsetByCodePoints(0, from)
    val endIndex = text.offsetByCodePoints(0, to)
    return text.substring(startIndex, endIndex)
}

private fun utf8Length(text: String): Int {
    var total = 0
    for (codePoint in codePointsOf(text)) total += utf8Length(codePoint)
    return total
}

private fun utf8Length(codePoint: Int): Int {
    return when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }
}

private fun u32(blob: ByteArray, offset: Int): Long {
    return (blob[offset].toLong() and 0xFF) or
        ((blob[offset + 1].toLong() and 0xFF) shl 8) or
        ((blob[offset + 2].toLong() and 0xFF) shl 16) or
        ((blob[offset + 3].toLong() and 0xFF) shl 24)
}

/**
 * Compiles one normalizer pattern from the multilingual tokenizer.
 *
 * Android's [java.util.regex.Pattern] throws
 * `IllegalArgumentException: UNICODE_CHARACTER_CLASS flag not supported`
 * when that flag is passed, which crashes process startup inside
 * [SemanticTransactionClassifier] class initialization. Unicode whitespace
 * is written out instead, so `\s` still matches the same characters the
 * model normalizer uses on the desktop JDK.
 */
internal fun compileNormalizerPattern(regex: String): Pattern {
    val portable = regex.replace("\\s", UNICODE_WHITE_SPACE_CLASS)
    return Pattern.compile(portable)
}

private const val UNICODE_WHITE_SPACE_CLASS =
    "[\\u0009-\\u000D\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]"

private fun hasLeaf(unit: Long): Boolean = ((unit shr 8) and 1L) == 1L

private fun valueOf(unit: Long): Int = (unit and 0x7FFFFFFFL).toInt()

private fun label(unit: Long): Int = (unit and 0x800000FFL).toInt()

private fun offset(unit: Long): Int {
    val shift = ((unit and 512L) shr 6).toInt()
    return (unit shr 10).toInt() shl shift
}
