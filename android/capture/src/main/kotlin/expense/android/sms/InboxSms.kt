package expense.android.sms

import expense.sms.InboundSms
import java.time.Instant

/**
 * One row from the SMS inbox provider, before it becomes an [InboundSms].
 * Strings are copied as stored. This type does not trim, translate, or fold digits.
 */
data class InboxSmsRow(
    val providerMessageId: String,
    val sender: String,
    val body: String,
    val receivedAtMillis: Long,
)

/**
 * Columns read from `content://sms/inbox`. Names match `android.provider.Telephony.Sms`.
 * The projection is the minimum needed to build [InboundSms].
 */
object InboxQuery {
    const val ID: String = "_id"
    const val ADDRESS: String = "address"
    const val BODY: String = "body"
    const val DATE: String = "date"

    val projection: Array<String> = arrayOf(ID, ADDRESS, BODY, DATE)
    const val sortOrder: String = "$DATE ASC, $ID ASC"
}

object InboxSmsConverter {
    fun convert(row: InboxSmsRow): InboundSms {
        return InboundSms(
            sender = row.sender,
            body = row.body,
            providerMessageId = row.providerMessageId,
            receivedAt = Instant.ofEpochMilli(row.receivedAtMillis),
        )
    }

    fun convertAll(rows: List<InboxSmsRow>): List<InboundSms> = rows.map(::convert)
}
