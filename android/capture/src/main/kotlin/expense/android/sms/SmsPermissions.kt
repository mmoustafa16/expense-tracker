package expense.android.sms

/**
 * Permissions this layer needs and nothing else.
 * [READ_SMS] scans the inbox. [RECEIVE_SMS] receives [SMS_RECEIVED_ACTION].
 * Sending, MMS, WAP push, and contacts are out of scope.
 */
object SmsPermissions {
    const val READ_SMS: String = "android.permission.READ_SMS"
    const val RECEIVE_SMS: String = "android.permission.RECEIVE_SMS"
    const val SMS_RECEIVED_ACTION: String = "android.provider.Telephony.SMS_RECEIVED"

    val required: Set<String> = setOf(READ_SMS, RECEIVE_SMS)
}
