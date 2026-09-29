package expense.android.sms

import android.content.ContentResolver
import android.provider.Telephony
import expense.sms.InboundSms
import expense.sms.SmsPages
import expense.sms.SmsSource

/**
 * Reads the on-device SMS inbox into [InboundSms] rows, oldest first.
 * Sent messages, drafts, and outbox rows are not queried.
 */
class ContentResolverInboxSource(
    private val resolver: ContentResolver,
    private val readGranted: () -> Boolean,
    private val after: InboxCursor? = null,
) : SmsSource {
    init {
        check(InboxQuery.ID == Telephony.Sms._ID)
        check(InboxQuery.ADDRESS == Telephony.Sms.ADDRESS)
        check(InboxQuery.BODY == Telephony.Sms.BODY)
        check(InboxQuery.DATE == Telephony.Sms.DATE)
    }

    override fun messages(): List<InboundSms> {
        val all = ArrayList<InboundSms>()
        forEachPage(SmsPages.DEFAULT_PAGE_SIZE) { all.addAll(it) }
        return all
    }

    override fun forEachPage(pageSize: Int, accept: (List<InboundSms>) -> Unit) {
        if (!readGranted()) {
            throw SecurityException("${SmsPermissions.READ_SMS} is required to scan the SMS inbox")
        }
        val cursor = resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            InboxQuery.projection,
            after?.selection(),
            after?.args(),
            InboxQuery.sortOrder,
        ) ?: return
        cursor.use { rows ->
            if (!rows.moveToFirst()) return@use
            val idIndex = rows.getColumnIndexOrThrow(InboxQuery.ID)
            val addressIndex = rows.getColumnIndexOrThrow(InboxQuery.ADDRESS)
            val bodyIndex = rows.getColumnIndexOrThrow(InboxQuery.BODY)
            val dateIndex = rows.getColumnIndexOrThrow(InboxQuery.DATE)
            val iterator = iterator {
                while (!rows.isAfterLast) {
                    yield(
                        InboxSmsConverter.convert(
                            InboxSmsRow(
                                providerMessageId = rows.getLong(idIndex).toString(),
                                sender = rows.readString(addressIndex),
                                body = rows.readString(bodyIndex),
                                receivedAtMillis = if (rows.isNull(dateIndex)) 0L else rows.getLong(dateIndex),
                            ),
                        ),
                    )
                    if (!rows.moveToNext()) break
                }
            }
            SmsPages.consume(iterator, pageSize, accept)
        }
    }

    private fun android.database.Cursor.readString(index: Int): String {
        if (isNull(index)) return ""
        return getString(index) ?: ""
    }
}
