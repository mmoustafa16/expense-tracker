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

    /**
     * Provider row order, which is arrival order. Ordering by date instead would
     * interleave rows whose network timestamp is wrong and make the position of
     * a partly read page meaningless.
     */
    const val sortOrder: String = "$ID ASC"
}

/**
 * Highest provider row id this device has already stored.
 *
 * The provider assigns ids in arrival order and never reuses one, so it is the
 * only field in an inbox row that is monotonic. A message's `date` comes from
 * the network: two messages can share one, and a sender or a carrier can stamp
 * one that is hours or days off. A cursor built on `date` therefore skips any
 * message whose timestamp sorts below a row already stored, and that message is
 * never read again no matter how many times the inbox is synced.
 *
 * Reading by id alone means a message is read exactly once. Protection against
 * re-ingesting the same text stays where it belongs, on the stored provider id
 * and the body hash.
 */
data class InboxCursor(
    val providerMessageId: Long,
) {
    fun selection(): String = "${InboxQuery.ID} > ?"

    fun args(): Array<String> = arrayOf(providerMessageId.toString())
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
