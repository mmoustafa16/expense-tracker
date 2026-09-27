package expense.android.sms

import expense.sms.InboundSms
import java.time.Instant

/**
 * One decoded SMS PDU. [body] is that segment only; multipart messages are joined later.
 */
data class DecodedSmsPart(
    val originatingAddress: String?,
    val body: String?,
    val timestampMillis: Long,
)

fun interface SmsPduDecoder {
    fun decode(pdu: ByteArray, format: String?): DecodedSmsPart?
}

/**
 * Turns an `SMS_RECEIVED` broadcast into [InboundSms] values.
 * The provider has not assigned an inbox id yet, so [InboundSms.providerMessageId] is null.
 * Text is concatenated in PDU order with no inserted separator, which is how the inbox stores it.
 */
object BroadcastSmsConverter {
    fun toInboundSms(
        action: String?,
        pduExtra: Any?,
        format: String?,
        decoder: SmsPduDecoder,
        receivedAtFallback: Instant,
    ): List<InboundSms> {
        if (action != SmsPermissions.SMS_RECEIVED_ACTION) return emptyList()
        val parts = pdusFrom(pduExtra).mapNotNull { pdu -> decoder.decode(pdu, format) }
        return joinParts(parts, receivedAtFallback)
    }

    fun joinParts(parts: List<DecodedSmsPart>, receivedAtFallback: Instant): List<InboundSms> {
        if (parts.isEmpty()) return emptyList()
        val messages = mutableListOf<InboundSms>()
        val body = StringBuilder()
        var sender: String? = null
        var timestampMillis: Long? = null

        fun flush() {
            val address = sender ?: return
            messages += InboundSms(
                sender = address,
                body = body.toString(),
                providerMessageId = null,
                receivedAt = timestampMillis?.let(Instant::ofEpochMilli) ?: receivedAtFallback,
            )
            body.clear()
            sender = null
            timestampMillis = null
        }

        for (part in parts) {
            val address = part.originatingAddress.orEmpty()
            if (sender != null && address != sender) flush()
            if (sender == null) sender = address
            if (timestampMillis == null && part.timestampMillis > 0L) {
                timestampMillis = part.timestampMillis
            }
            body.append(part.body.orEmpty())
        }
        flush()
        return messages
    }

    private fun pdusFrom(extra: Any?): List<ByteArray> {
        val array = extra as? Array<*> ?: return emptyList()
        return array.mapNotNull { it as? ByteArray }
    }
}
