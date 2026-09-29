package expense.android.sms

import android.content.Context
import android.content.pm.PackageManager
import expense.sms.SmsSource

/**
 * Reads the two SMS permissions. It does not show a permission UI.
 */
class SmsAccess(private val context: Context) {
    fun canReadInbox(): Boolean = granted(SmsPermissions.READ_SMS)

    fun canReceiveSms(): Boolean = granted(SmsPermissions.RECEIVE_SMS)

    fun inboxSource(after: InboxCursor? = null): SmsSource {
        return ContentResolverInboxSource(context.contentResolver, ::canReadInbox, after)
    }

    private fun granted(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
