package expense.android

import android.app.Application
import expense.android.sms.PipelineSmsSink
import expense.android.sms.SmsAccess

/**
 * Application shell. There is no activity yet. Incoming SMS is delivered to
 * [IncomingSmsReceiver], which calls [smsIngestion]. Inbox scans are explicit
 * because they require [expense.android.sms.SmsPermissions.READ_SMS].
 */
class ExpenseTrackerApplication : Application() {
    val smsIngestion: PipelineSmsSink = PipelineSmsSink()

    fun ingestInbox() {
        smsIngestion.ingest(SmsAccess(this).inboxSource())
    }
}
