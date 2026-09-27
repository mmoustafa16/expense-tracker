package expense.android.sms

import android.telephony.SmsMessage

/**
 * Decodes broadcast PDUs with the platform SMS parser.
 * [SmsMessage.getMessageBody] is used so the text is not display-normalized.
 */
class AndroidSmsPduDecoder : SmsPduDecoder {
    override fun decode(pdu: ByteArray, format: String?): DecodedSmsPart? {
        val message = decodeMessage(pdu, format) ?: return null
        return DecodedSmsPart(
            originatingAddress = message.originatingAddress,
            body = message.messageBody,
            timestampMillis = message.timestampMillis,
        )
    }

    private fun decodeMessage(pdu: ByteArray, format: String?): SmsMessage? {
        return try {
            if (format.isNullOrEmpty()) {
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(pdu)
            } else {
                SmsMessage.createFromPdu(pdu, format)
            }
        } catch (_: RuntimeException) {
            null
        }
    }
}
