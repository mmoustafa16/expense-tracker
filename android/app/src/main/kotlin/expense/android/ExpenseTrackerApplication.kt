package expense.android

import android.app.Application
import expense.android.sms.InboundSmsSink
import expense.android.sms.InboxCursor
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
 * calls [smsIngestion]. Each unlock syncs inbox rows newer than the last
 * stored provider message. [SMS_RECEIVED] still ingests messages while the
 * process is alive. A later sync dedupes those rows.
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
        val access = SmsAccess(this)
        if (!access.canReadInbox()) return
        if (!session.isUnlocked()) return
        if (!scanGate.tryStart()) return
        try {
            val reclassified = reclassifyStoredMessages()
            val relinked = session.relinkStoredAccounts()
            val cursor = session.inboxCursor()?.let { (receivedAt, providerId) ->
                InboxCursor(receivedAt, providerId)
            }
            var ingested = false
            session.ingest(access.inboxSource(cursor)) { tally ->
                ingested = true
                inboxScanState.value = inboxScanState.value.copy(
                    phase = InboxScanPhase.RUNNING,
                    tally = tally,
                )
            }
            scanGate.finish()
            if (reclassified || relinked > 0 || ingested) {
                val generation = inboxScanState.value.generation + 1
                inboxScanState.value = InboxScan(
                    phase = InboxScanPhase.FINISHED,
                    tally = session.storedTally(),
                    generation = generation,
                )
            }
        } catch (_: SecurityException) {
            scanGate.abandon()
            inboxScanState.value = InboxScan()
        } catch (error: RuntimeException) {
            scanGate.abandon()
            inboxScanState.value = InboxScan()
            throw error
        }
    }

    private fun reclassifyStoredMessages(): Boolean {
        if (!session.isUnlocked()) return false
        val prefs = getSharedPreferences(SETUP_PREFS, MODE_PRIVATE)
        if (prefs.getInt(CLASSIFICATION_REVISION_KEY, 0) >= CLASSIFICATION_REVISION) return false
        session.reclassifyRetained()
        prefs.edit().putInt(CLASSIFICATION_REVISION_KEY, CLASSIFICATION_REVISION).apply()
        return true
    }

    private companion object {
        const val SETUP_PREFS = "expense_setup"
        const val CLASSIFICATION_REVISION_KEY = "classification_revision"
        const val CLASSIFICATION_REVISION = 5
    }
}
