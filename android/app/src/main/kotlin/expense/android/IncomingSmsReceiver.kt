package expense.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import expense.android.sms.SmsBroadcasts

/**
 * Manifest receiver for `android.provider.Telephony.SMS_RECEIVED`.
 * It converts the broadcast and passes the result to the ingest pipeline.
 * It does not abort the broadcast or write the inbox.
 */
class IncomingSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val app = context.applicationContext as? ExpenseTrackerApplication ?: return
        app.smsIngestion.accept(SmsBroadcasts.read(intent))
    }
}
