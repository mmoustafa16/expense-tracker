package expense.android

import android.app.Application
import expense.android.sms.InboundSmsSink
import expense.android.sms.SmsAccess
import expense.android.sms.SmsPermissions
import expense.android.storage.LedgerSession
import expense.android.storage.LedgerSessions
import expense.android.storage.UnlockPrompt
import expense.android.storage.UnlockResult
import expense.ingest.IngestTally
import expense.ingest.SemanticsWarmup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Application shell. [MainActivity] shows the cold-start unlock and the local
 * ledger screens. Incoming SMS is delivered to [IncomingSmsReceiver], which
 * calls [smsIngestion]. The first granted inbox read scans the available SMS
 * inbox. Later messages arrive through the receiver.
 *
 * The encrypted ledger stays locked until [unlockLedger]. Messages accepted
 * before that stay in process memory and are written after the cold-start unlock.
 */
class ExpenseTrackerApplication : Application() {
    private val session: LedgerSession by lazy { LedgerSessions.android(this) }
    private val scanGate = InboxScanGate()
    private val inboxScanState = MutableStateFlow(InboxScan())

    val inboxScan: StateFlow<InboxScan> = inboxScanState

    override fun onCreate() {
        super.onCreate()
        Thread({ SemanticsWarmup.start() }, "semantic-model").apply {
            isDaemon = true
            start()
        }
    }

    val smsIngestion: InboundSmsSink = InboundSmsSink { messages ->
        session.accept(messages)
    }

    fun ledger(): LedgerSession = session

    fun ingestInbox() {
        session.ingest(SmsAccess(this).inboxSource())
    }

    fun unlockLedger(prompt: UnlockPrompt, onResult: (UnlockResult) -> Unit) {
        session.unlock(prompt, onResult)
    }

    fun missingSmsPermissions(): Set<String> {
        val access = SmsAccess(this)
        return buildSet {
            if (!access.canReadInbox()) add(SmsPermissions.READ_SMS)
            if (!access.canReceiveSms()) add(SmsPermissions.RECEIVE_SMS)
        }
    }

    fun scanInboxIfGranted() {
        if (inboxScanned()) {
            if (reclassifyStoredMessages()) publishStoredSummary()
            return
        }
        val access = SmsAccess(this)
        if (!access.canReadInbox()) return
        if (!session.isUnlocked()) return
        if (!scanGate.tryStart()) return
        try {
            inboxScanState.value = InboxScan(phase = InboxScanPhase.RUNNING, tally = IngestTally())
            session.ingest(access.inboxSource()) { tally ->
                inboxScanState.value = InboxScan(phase = InboxScanPhase.RUNNING, tally = tally)
            }
            getSharedPreferences(SETUP_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(INBOX_SCANNED, true)
                .putInt(CLASSIFICATION_REVISION_KEY, CLASSIFICATION_REVISION)
                .apply()
            scanGate.finish()
            inboxScanState.value = InboxScan(phase = InboxScanPhase.FINISHED, tally = session.storedTally())
        } catch (_: SecurityException) {
            scanGate.abandon()
            inboxScanState.value = InboxScan()
        } catch (error: RuntimeException) {
            scanGate.abandon()
            inboxScanState.value = InboxScan()
            throw error
        }
    }

    private fun publishStoredSummary() {
        if (inboxScanState.value.phase == InboxScanPhase.RUNNING) return
        if (!session.isUnlocked()) return
        inboxScanState.value = InboxScan(phase = InboxScanPhase.FINISHED, tally = session.storedTally())
    }

    private fun reclassifyStoredMessages(): Boolean {
        if (!session.isUnlocked()) return false
        val prefs = getSharedPreferences(SETUP_PREFS, MODE_PRIVATE)
        if (prefs.getInt(CLASSIFICATION_REVISION_KEY, 0) >= CLASSIFICATION_REVISION) return false
        session.reclassifyRetained()
        prefs.edit().putInt(CLASSIFICATION_REVISION_KEY, CLASSIFICATION_REVISION).apply()
        return true
    }

    private fun inboxScanned(): Boolean {
        return getSharedPreferences(SETUP_PREFS, MODE_PRIVATE).getBoolean(INBOX_SCANNED, false)
    }

    private companion object {
        const val SETUP_PREFS = "expense_setup"
        const val INBOX_SCANNED = "inbox_scanned"
        const val CLASSIFICATION_REVISION_KEY = "classification_revision"
        const val CLASSIFICATION_REVISION = 4
    }
}
