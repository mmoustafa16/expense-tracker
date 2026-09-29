package expense.intelligence

import expense.parse.AccountKind
import expense.parse.FinancialEventType

/**
 * What this device has observed from one sender address, in aggregate.
 *
 * No single message makes an address an institution. A hospital, a restaurant,
 * a delivery service, and a telecom marketing channel all send from
 * alphanumeric addresses, and one of their messages can look financial. What
 * separates a notifying institution from them is the shape of its whole
 * history: it reports completed money movements repeatedly, in more than one
 * form, and it identifies instruments with consistent masks.
 *
 * This record holds counts and shapes only. It never holds message text, an
 * amount, a merchant, or a name the account holder did not already see.
 */
data class SenderEvidence(
    val sender: String,
    val financialEvents: Int = 0,
    val movementEvents: Int = 0,
    val nonFinancialEvents: Int = 0,
    val eventTypes: Set<FinancialEventType> = emptySet(),
    val instrumentMasks: Set<String> = emptySet(),
    val instrumentKinds: Set<AccountKind> = emptySet(),
) {
    val observations: Int get() = financialEvents + nonFinancialEvents

    /** Distinct event types that can move money. One form is a coincidence. */
    val movementEventTypes: Int get() = eventTypes.count { it.canMoveMoney() }

    /** Every observed mask has the same length, as an issuer's masks do. */
    val consistentMasks: Boolean
        get() = instrumentMasks.isNotEmpty() && instrumentMasks.map { it.length }.distinct().size == 1

    fun observing(
        eventType: FinancialEventType,
        moneyMovement: Boolean,
        instrument: PaymentInstrument?,
    ): SenderEvidence {
        val financial = eventType.isFinancialEvent()
        return copy(
            financialEvents = financialEvents + if (financial) 1 else 0,
            movementEvents = movementEvents + if (financial && moneyMovement) 1 else 0,
            nonFinancialEvents = nonFinancialEvents + if (financial) 0 else 1,
            eventTypes = if (financial) eventTypes + eventType else eventTypes,
            instrumentMasks = instrument?.let { instrumentMasks + it.mask } ?: instrumentMasks,
            instrumentKinds = instrument?.let { instrumentKinds + it.kind } ?: instrumentKinds,
        )
    }

    operator fun plus(other: SenderEvidence): SenderEvidence {
        return SenderEvidence(
            sender = sender,
            financialEvents = financialEvents + other.financialEvents,
            movementEvents = movementEvents + other.movementEvents,
            nonFinancialEvents = nonFinancialEvents + other.nonFinancialEvents,
            eventTypes = eventTypes + other.eventTypes,
            instrumentMasks = instrumentMasks + other.instrumentMasks,
            instrumentKinds = instrumentKinds + other.instrumentKinds,
        )
    }
}

/**
 * Whether accumulated evidence is enough to let a sender authorize ledger posts.
 *
 * The thresholds are about behaviour, not identity. An address that clears them
 * has behaved like a notifying institution regardless of who runs it, and an
 * address that does not stays unverified no matter how many messages it sends.
 * A sender below the bar is not rejected: its transactions wait in review,
 * where the account holder can act on them.
 */
data class InstitutionEvidencePolicy(
    val minimumFinancialEvents: Int = 3,
    val minimumMovementEvents: Int = 2,
    val minimumMovementEventTypes: Int = 2,
) {
    fun verifies(evidence: SenderEvidence): Boolean {
        if (!isInstitutionalChannel(evidence.sender)) return false
        if (evidence.financialEvents < minimumFinancialEvents) return false
        if (evidence.movementEvents < minimumMovementEvents) return false
        if (evidence.financialEvents <= evidence.nonFinancialEvents) return false
        val identifiesInstruments = evidence.consistentMasks
        val reportsSeveralForms = evidence.movementEventTypes >= minimumMovementEventTypes
        return identifiesInstruments || reportsSeveralForms
    }

    companion object {
        val DEFAULT: InstitutionEvidencePolicy = InstitutionEvidencePolicy()
    }
}

/** Read access to what this device has observed. Empty on a fresh install. */
fun interface SenderEvidenceSource {
    fun evidenceFor(sender: String): SenderEvidence?

    companion object {
        val EMPTY: SenderEvidenceSource = SenderEvidenceSource { null }
    }
}

/**
 * Accumulates evidence during a scan. The ingest pipeline records one
 * observation per message and the storage layer persists the totals, so a
 * sender that earned verification keeps it across a restart.
 */
class MutableSenderEvidenceLedger(
    initial: Collection<SenderEvidence> = emptyList(),
) : SenderEvidenceSource {
    private val byId = LinkedHashMap<String, SenderEvidence>()
    private val touched = LinkedHashSet<String>()

    init {
        initial.forEach { record ->
            val key = record.sender.trim()
            if (key.isNotEmpty()) byId[key] = record.copy(sender = key)
        }
    }

    override fun evidenceFor(sender: String): SenderEvidence? = byId[sender.trim()]

    /**
     * Installs totals read back from storage. Loading is not an observation, so
     * the loaded records are not reported as changed.
     */
    fun preload(records: Collection<SenderEvidence>) {
        records.forEach { record ->
            val key = record.sender.trim()
            if (key.isNotEmpty()) byId[key] = record.copy(sender = key)
        }
        touched.clear()
    }

    fun observe(
        sender: String,
        eventType: FinancialEventType,
        moneyMovement: Boolean,
        instrument: PaymentInstrument?,
    ) {
        val key = sender.trim()
        if (key.isEmpty()) return
        val current = byId[key] ?: SenderEvidence(key)
        byId[key] = current.observing(eventType, moneyMovement, instrument)
        touched += key
    }

    fun snapshot(): List<SenderEvidence> = byId.values.toList()

    /** Records changed since the last [clearChanged]. The storage layer writes these. */
    fun changed(): List<SenderEvidence> = touched.mapNotNull { byId[it] }

    fun clearChanged() {
        touched.clear()
    }
}
