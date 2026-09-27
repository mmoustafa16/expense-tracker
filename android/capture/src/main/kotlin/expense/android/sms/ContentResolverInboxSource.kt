package expense.android.sms

import android.content.ContentResolver
import android.provider.Telephony
import expense.sms.InboundSms
import expense.sms.SmsSource

/**
 * Reads the on-device SMS inbox into [InboundSms] rows, oldest first.
 * Sent messages, drafts, and outbox rows are not queried.
 */
class ContentResolverInboxSource(
    private val resolver: ContentResolver,
    private val readGranted: () -> Boolean,
) : SmsSource {
    init {
        check(InboxQuery.ID == Telephony.Sms._ID)
        check(InboxQuery.ADDRESS == Telephony.Sms.ADDRESS)
        check(InboxQuery.BODY == Telephony.Sms.BODY)
        check(InboxQuery.DATE == Telephony.Sms.DATE)
    }

    override fun messages(): List<InboundSms> {
        if (!readGranted()) {
            throw SecurityException("${SmsPermissions.READ_SMS} is required to scan the SMS inbox")
        }
        val cursor = resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            InboxQuery.projection,
            null,
            null,
            InboxQuery.sortOrder,
        ) ?: return emptyList()
        try {
            val idIndex = cursor.getColumnIndexOrThrow(InboxQuery.ID)
            val addressIndex = cursor.getColumnIndexOrThrow(InboxQuery.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(InboxQuery.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(InboxQuery.DATE)
            return buildList(cursor.count.coerceAtLeast(0)) {
                while (cursor.moveToNext()) {
                    add(
                        InboxSmsConverter.convert(
                            InboxSmsRow(
                                providerMessageId = cursor.getLong(idIndex).toString(),
                                sender = cursor.readString(addressIndex),
                                body = cursor.readString(bodyIndex),
                                receivedAtMillis = if (cursor.isNull(dateIndex)) 0L else cursor.getLong(dateIndex),
                            ),
                        ),
                    )
                }
            }
        } finally {
            cursor.close()
        }
    }

    private fun android.database.Cursor.readString(index: Int): String {
        if (isNull(index)) return ""
        return getString(index) ?: ""
    }
}
