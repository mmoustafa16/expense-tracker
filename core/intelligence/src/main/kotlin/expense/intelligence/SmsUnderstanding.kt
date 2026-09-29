package expense.intelligence

import expense.money.Currency
import expense.money.Money
import expense.parse.AccountKind
import expense.parse.AmountResolution
import expense.parse.AmountRole
import expense.parse.Direction
import expense.parse.EventAmounts
import expense.parse.FinancialEventType
import expense.parse.RoledAmount
import expense.parse.SpendEffect
import java.time.LocalDateTime

/**
 * Text the intelligence layer may read. Callers on the device pass the local SMS.
 * Nothing in this pipeline sends the text to a network service.
 */
data class SmsText(
    val sender: String,
    val body: String,
)

/**
 * How strongly the message supports the claim that a financial event happened.
 *
 * A label on its own is not evidence. [STRONG] needs either a confident model
 * prediction or corroboration from the message itself: money with a currency,
 * plus a completion cue, an instrument, or a balance. Anything less is [WEAK]
 * and is ignored rather than held for review, which is what keeps ordinary
 * service messages out of the review queue.
 */
enum class FinancialEvidence {
    NONE,
    WEAK,
    STRONG,
}

/** Whether the event happened and whether money actually moved. */
data class EventState(
    val completed: Boolean,
    val moneyMovement: Boolean,
    val evidence: FinancialEvidence,
)

/**
 * Decides completion and movement from the message itself instead of from a
 * table keyed by the predicted label. An announcement about a future deduction
 * is not a completed event even when it names an amount.
 *
 * The Stage 2 span model implements this interface with predicted flags.
 */
fun interface EventStateDetector {
    fun detect(
        message: SmsText,
        classification: Classification,
        entities: ExtractedEntities,
    ): EventState
}

/**
 * Verified sender aliases for one institution.
 * [senderIds] may contain more than one alias. An alias is added only when a
 * local fixture has verified it. Matching is an exact trim, not a guess.
 */
data class RegisteredSender(
    val institutionId: String,
    val displayName: String,
    val senderIds: Set<String>,
)

/**
 * Meaning of the whole SMS as the on-device model sees it.
 * [intent] is the model's own label; the pipeline reads [Classification.eventType].
 */
data class SmsSemantics(
    val intent: String,
    val direction: Direction?,
    val confidence: Int,
)

data class Classification(
    val eventType: FinancialEventType,
    val confidence: Int,
    val ambiguous: Boolean,
    val semantics: SmsSemantics? = null,
)

/**
 * Assigns an event type from the meaning of the whole message.
 * The bundled implementation is an on-device model. Another model can
 * implement this without changing discovery, validation, or the ledger.
 * Implementations must not send the SMS off the device or log its body.
 */
fun interface TransactionClassifier {
    fun classify(message: SmsText): Classification
}

/** Card or account last four digits the message states, and what it is. */
data class PaymentInstrument(
    val mask: String,
    val kind: AccountKind,
)

/**
 * Entities copied out of one message. Every money value carries an
 * [AmountRole], so a balance, a credit limit, and an advertised ceiling are
 * not candidates for the value of the event.
 */
data class ExtractedEntities(
    val amounts: List<RoledAmount> = emptyList(),
    val resolution: AmountResolution = AmountResolution.MISSING,
    val merchant: String? = null,
    val instrument: PaymentInstrument? = null,
    val reference: String? = null,
    val occurredAt: LocalDateTime? = null,
    val direction: Direction? = null,
    /**
     * Which roles may carry the value of the event, most specific first. The
     * extractor does not know the event type, so it uses the general order and
     * the pipeline narrows it once the type is decided.
     */
    val valueRoles: List<AmountRole> = AmountRole.DEFAULT_VALUE_ROLES,
) {
    /** The single amount that carries the value of the event, when there is one. */
    val eventAmount: RoledAmount?
        get() = if (resolution == AmountResolution.RESOLVED) {
            EventAmounts.select(amounts, valueRoles)
        } else {
            null
        }

    val amount: Money? get() = eventAmount?.amount

    val currency: Currency? get() = eventAmount?.amount?.currency

    /** First balance-shaped value, used for the account balance on a ledger row. */
    val balance: Money? get() = amounts.firstOrNull { it.role.isBalance() }?.amount

    /** Everything except the event value, each with its role. */
    val relatedAmounts: List<RoledAmount>
        get() {
            val event = eventAmount ?: return amounts
            return amounts.filterNot { it.start == event.start && it.end == event.end }
        }

    val accountMask: String? get() = instrument?.mask
}

/**
 * Copies entities that are present in the message.
 * Missing fields stay null. The Stage 2 span model implements this interface.
 */
fun interface FinancialEntityExtractor {
    fun extract(message: SmsText): ExtractedEntities
}

/**
 * Assigns a role to every money value found in a message.
 * Implementations must not look at the sender.
 */
fun interface AmountRoleTagger {
    fun tag(body: String, amounts: List<FoundAmount>): List<RoledAmount>
}

/** A money value located in the text before any role has been assigned. */
data class FoundAmount(
    val amount: Money,
    val token: String,
    val currencyToken: String,
    val start: Int,
    val end: Int,
)

/**
 * Whether the claimed entities are traceable to the message.
 * This checks extraction validity only. Whether an event happened, and whether
 * the amount was found, are decided by [EventStateDetector] and [FinancialRouting].
 */
data class ValidationResult(
    val accepted: Boolean,
    val contradictions: List<String>,
)

fun interface TransactionValidator {
    fun validate(message: SmsText, entities: ExtractedEntities): ValidationResult
}

/**
 * The structured result of understanding one message. This is what the ledger,
 * the review queue, and the counters all read. It separates what happened
 * ([eventType]), whether it happened ([completed]), whether money moved
 * ([moneyMovement]), what it does to spending ([spendEffect]), and what the
 * numbers in the message meant ([amount] and [relatedAmounts]).
 */
data class FinancialEvent(
    val eventType: FinancialEventType,
    val completed: Boolean,
    val moneyMovement: Boolean,
    val direction: Direction?,
    val spendEffect: SpendEffect,
    val amount: Money?,
    val currency: Currency?,
    val relatedAmounts: List<RoledAmount>,
    val merchant: String?,
    val institutionId: String?,
    val institutionName: String?,
    val instrumentType: AccountKind?,
    val instrumentLast4: String?,
    val occurredAt: LocalDateTime?,
    val reference: String?,
    val evidence: FinancialEvidence,
    val confidence: Int,
) {
    val financial: Boolean get() = eventType.isFinancialEvent()
}

enum class RoutingOutcome {
    /** Becomes a ledger transaction. */
    POST,

    /** A completed movement the pipeline could not finish. The user decides. */
    REVIEW,

    /** Not a completed money movement. No ledger row and no review item. */
    IGNORE,
}

data class Routing(
    val outcome: RoutingOutcome,
    val holdReason: String?,
)

data class ClassificationDecision(
    val discovery: BankDiscoveryResult,
    val classification: Classification,
    val entities: ExtractedEntities,
    val state: EventState,
    val validation: ValidationResult,
    val event: FinancialEvent,
    val routing: Routing,
) {
    val postable: Boolean get() = routing.outcome == RoutingOutcome.POST

    /** Why a completed movement was not posted. Null when it posted or was ignored. */
    val holdReason: String? get() = routing.holdReason
}
