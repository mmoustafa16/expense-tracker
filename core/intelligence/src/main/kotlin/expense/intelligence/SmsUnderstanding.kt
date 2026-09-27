package expense.intelligence

import expense.money.Currency
import expense.money.Money
import java.time.LocalDateTime

/**
 * Text the intelligence layer may read. Callers on the device pass the local SMS.
 * Nothing in this pipeline sends the text to a network service.
 */
data class SmsText(
    val sender: String,
    val body: String,
)

enum class TransactionClass {
    CARD_PURCHASE,
    TRANSFER,
    CASH_WITHDRAWAL,
    REFUND,
    REVERSAL,
    FEE,
    PAYMENT,
    BALANCE_NOTIFICATION,
    STATEMENT,
    PAYMENT_DUE,
    OTP,
    PROMOTION,
    OTHER_NON_TRANSACTION,
    ;

    fun isLedgerCandidate(): Boolean = when (this) {
        CARD_PURCHASE, TRANSFER, CASH_WITHDRAWAL, REFUND, REVERSAL, FEE, PAYMENT -> true
        BALANCE_NOTIFICATION, STATEMENT, PAYMENT_DUE, OTP, PROMOTION, OTHER_NON_TRANSACTION -> false
    }
}

enum class ConfidenceLevel {
    /** Eligible for automatic ledger posting after validation. */
    HIGH,

    /** Keep the message for review. */
    MEDIUM,

    /** Review when it might be a transaction. Ignore when it is not. */
    LOW,
}

enum class MoneyDirection {
    DEBIT,
    CREDIT,
}

enum class AmountRole {
    ABSENT,
    TRANSACTION,
    BALANCE,
    AMBIGUOUS,
}

data class RegisteredSender(
    val institutionId: String,
    val displayName: String,
    val senderIds: Set<String>,
)

data class BankCandidate(
    val institutionId: String,
    val displayName: String,
    val confidence: Int,
)

data class BankIdentification(
    val candidates: List<BankCandidate>,
    val unknown: Boolean,
)

/**
 * Identifies a probable institution from a sender that was registered after verification.
 * An unregistered sender stays unknown. This type must not invent sender ids.
 */
fun interface BankIdentifier {
    fun identify(message: SmsText): BankIdentification
}

data class Classification(
    val type: TransactionClass,
    val confidence: Int,
    val ambiguous: Boolean,
)

/**
 * Assigns a transaction class and a confidence score.
 * A later on-device model can implement this without changing the ledger.
 */
fun interface TransactionClassifier {
    fun classify(message: SmsText): Classification
}

data class ExtractedEntities(
    val amount: Money? = null,
    val amountToken: String? = null,
    val currency: Currency? = null,
    val currencyToken: String? = null,
    val merchant: String? = null,
    val accountMask: String? = null,
    val reference: String? = null,
    val occurredAt: LocalDateTime? = null,
    val direction: MoneyDirection? = null,
    val balance: Money? = null,
    val amountRole: AmountRole = AmountRole.ABSENT,
)

/**
 * Copies entities that are present in the message.
 * Missing fields stay null. A later on-device model can implement this.
 */
fun interface FinancialEntityExtractor {
    fun extract(message: SmsText): ExtractedEntities
}

data class ValidationResult(
    val accepted: Boolean,
    val contradictions: List<String>,
    val forcesReview: Boolean,
    val ledgerForbidden: Boolean,
)

/**
 * Checks classifier and extractor output against the original SMS.
 * A model result is not a ledger row until this accepts it.
 */
fun interface TransactionValidator {
    fun validate(
        message: SmsText,
        classification: Classification,
        entities: ExtractedEntities,
    ): ValidationResult
}

data class ClassificationDecision(
    val identification: BankIdentification,
    val classification: Classification,
    val entities: ExtractedEntities,
    val validation: ValidationResult,
    val level: ConfidenceLevel,
) {
    val postable: Boolean =
        level == ConfidenceLevel.HIGH &&
            validation.accepted &&
            !validation.forcesReview &&
            !validation.ledgerForbidden &&
            entities.amount != null
}
