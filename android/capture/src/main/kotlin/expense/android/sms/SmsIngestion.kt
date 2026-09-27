package expense.android.sms

import expense.ingest.IngestPipeline
import expense.ledger.LedgerState
import expense.sms.InboundSms
import expense.sms.SmsSource

fun interface InboundSmsSink {
    fun accept(messages: List<InboundSms>)
}

/**
 * Process-local ledger. This is not a database; state is discarded when the process dies.
 */
class InMemoryLedgerStore(
    initial: LedgerState = LedgerState.empty(),
) {
    private val lock = Any()
    private var current: LedgerState = initial

    fun get(): LedgerState = synchronized(lock) { current }

    fun update(transform: (LedgerState) -> LedgerState): LedgerState = synchronized(lock) {
        val next = transform(current)
        current = next
        next
    }
}

/**
 * Hands captured SMS to [IngestPipeline] and keeps the resulting [LedgerState] in memory.
 * Bank profiles, categories, and storage stay outside this type. The default pipeline
 * uses [expense.parse.BankRegistry.EMPTY].
 */
class PipelineSmsSink(
    private val pipeline: IngestPipeline = IngestPipeline(),
    private val ledger: InMemoryLedgerStore = InMemoryLedgerStore(),
) : InboundSmsSink {
    fun ledgerState(): LedgerState = ledger.get()

    override fun accept(messages: List<InboundSms>) {
        if (messages.isEmpty()) return
        ingest(SmsSource { messages })
    }

    fun ingest(source: SmsSource) {
        ledger.update { state -> pipeline.ingestAll(source, state) }
    }
}
