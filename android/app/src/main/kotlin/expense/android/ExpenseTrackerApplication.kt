package expense.android

import android.app.Application
import expense.android.sms.InboundSmsSink
import expense.android.sms.SmsAccess
import expense.android.storage.LedgerSession
import expense.android.storage.LedgerSessions
import expense.android.storage.UnlockPrompt
import expense.android.storage.UnlockResult

/**
 * Application shell. There is no activity yet. Incoming SMS is delivered to
 * [IncomingSmsReceiver], which calls [smsIngestion]. Inbox scans are explicit
 * because they require [expense.android.sms.SmsPermissions.READ_SMS].
 *
 * The encrypted ledger stays locked until [unlockLedger]. Messages accepted
 * before that stay in process memory and are written after the cold-start unlock.
 */
class ExpenseTrackerApplication : Application() {
    private val session: LedgerSession by lazy { LedgerSessions.android(this) }

    val smsIngestion: InboundSmsSink = InboundSmsSink { messages ->
        session.accept(messages)
    }

    fun ingestInbox() {
        session.ingest(SmsAccess(this).inboxSource())
    }

    fun unlockLedger(prompt: UnlockPrompt, onResult: (UnlockResult) -> Unit) {
        session.unlock(prompt, onResult)
    }
}
