package expense.intelligence

/**
 * Where an institution guess came from. Sources are combined by
 * [CompositeBankDiscovery]. Only [authorizesLedger] sources may allow a
 * ledger post. The others may suggest a candidate and nothing more.
 *
 * No source in this module calls a network service or reads a real SMS.
 */
enum class DiscoverySourceKind {
    VERIFIED_SENDER_REGISTRY,
    BANK_EVIDENCE,
    PUBLIC_BANK_METADATA,
    USER_CONFIRMED_SENDER,
    ON_DEVICE_MODEL,
    LOCAL_LEARNED_PATTERN,
    ;

    /** The verified sender registry is the only source that may authorize a ledger post. */
    fun authorizesLedger(): Boolean = this == VERIFIED_SENDER_REGISTRY
}

enum class DiscoveryStatus {
    /** Exactly one institution. [DiscoveredInstitution.verified] says whether it may post. */
    KNOWN,

    /** No institution was found. */
    UNKNOWN,

    /** More than one institution. The ledger must not pick one. */
    AMBIGUOUS,
}

data class DiscoveredInstitution(
    val institutionId: String,
    val displayName: String,
    val confidence: Int,
    val source: DiscoverySourceKind,
    val verified: Boolean,
    val evidence: List<MatchedEvidence> = emptyList(),
)

data class BankDiscoveryResult(
    val status: DiscoveryStatus,
    val candidates: List<DiscoveredInstitution>,
) {
    /**
     * The single institution allowed to authorize a ledger post.
     * Unknown, ambiguous, and unverified results are null.
     */
    val verifiedInstitution: DiscoveredInstitution?
        get() = if (status == DiscoveryStatus.KNOWN) {
            candidates.singleOrNull()?.takeIf { it.verified }
        } else {
            null
        }

    companion object {
        fun unknown(): BankDiscoveryResult = BankDiscoveryResult(DiscoveryStatus.UNKNOWN, emptyList())
    }
}

/**
 * Replaceable bank discovery. Transaction classification does not call this.
 */
fun interface BankDiscovery {
    fun discover(message: SmsText): BankDiscoveryResult
}

/**
 * One future or current way to guess an institution.
 * Implementations must not invent a sender id and must not call a network API.
 */
interface BankDiscoverySource {
    val kind: DiscoverySourceKind

    fun discover(message: SmsText): List<DiscoveredInstitution>
}

/**
 * Exact sender match against institutions registered after verification.
 * An unregistered sender contributes nothing.
 */
class VerifiedSenderRegistry(
    private val senders: List<RegisteredSender>,
) : BankDiscoverySource {
    override val kind: DiscoverySourceKind = DiscoverySourceKind.VERIFIED_SENDER_REGISTRY

    override fun discover(message: SmsText): List<DiscoveredInstitution> {
        val trimmed = message.sender.trim()
        if (trimmed.isEmpty()) return emptyList()
        return senders.filter { sender -> sender.senderIds.any { it.trim() == trimmed } }
            .map { sender ->
                DiscoveredInstitution(
                    institutionId = sender.institutionId,
                    displayName = sender.displayName,
                    confidence = VERIFIED_CONFIDENCE,
                    source = kind,
                    verified = true,
                    evidence = listOf(MatchedEvidence(EvidenceKind.SENDER_ALIAS, trimmed)),
                )
            }
    }

    private companion object {
        const val VERIFIED_CONFIDENCE: Int = 90
    }
}

/**
 * Placeholder for official or public bank metadata.
 * Empty until a verified metadata set exists. This type does not fetch anything.
 */
class PublicBankMetadata : BankDiscoverySource {
    override val kind: DiscoverySourceKind = DiscoverySourceKind.PUBLIC_BANK_METADATA

    override fun discover(message: SmsText): List<DiscoveredInstitution> = emptyList()
}

data class UserConfirmedSender(
    val institutionId: String,
    val displayName: String,
    val senderId: String,
)

/**
 * Sender identities the account holder confirmed on the device.
 * A confirmation can name an institution. It cannot authorize a ledger post.
 * Nothing is confirmed by default.
 */
class UserConfirmedSenders(
    private val confirmations: List<UserConfirmedSender> = emptyList(),
) : BankDiscoverySource {
    override val kind: DiscoverySourceKind = DiscoverySourceKind.USER_CONFIRMED_SENDER

    override fun discover(message: SmsText): List<DiscoveredInstitution> {
        val trimmed = message.sender.trim()
        if (trimmed.isEmpty()) return emptyList()
        return confirmations.filter { it.senderId.trim() == trimmed }.map { confirmed ->
            DiscoveredInstitution(
                institutionId = confirmed.institutionId,
                displayName = confirmed.displayName,
                confidence = CONFIRMED_CONFIDENCE,
                source = kind,
                verified = false,
                evidence = listOf(MatchedEvidence(EvidenceKind.SENDER_ALIAS, trimmed)),
            )
        }
    }

    private companion object {
        const val CONFIRMED_CONFIDENCE: Int = 95
    }
}

/**
 * Plug-in point for a future on-device model.
 * No model is bundled, and this type does not call a cloud model.
 */
class OnDeviceModelDiscovery : BankDiscoverySource {
    override val kind: DiscoverySourceKind = DiscoverySourceKind.ON_DEVICE_MODEL

    override fun discover(message: SmsText): List<DiscoveredInstitution> = emptyList()
}

/**
 * Plug-in point for patterns learned on the device later.
 * Nothing has been learned. This type does not read an inbox.
 */
class LocalLearnedPatterns : BankDiscoverySource {
    override val kind: DiscoverySourceKind = DiscoverySourceKind.LOCAL_LEARNED_PATTERN

    override fun discover(message: SmsText): List<DiscoveredInstitution> = emptyList()
}

/**
 * Runs every [BankDiscoverySource] and merges institution ids.
 * Only a [VerifiedSenderRegistry] hit can stay verified. Evidence, metadata,
 * a model, a learned pattern, or a user confirmation cannot.
 */
class CompositeBankDiscovery(
    private val sources: List<BankDiscoverySource>,
) : BankDiscovery {
    override fun discover(message: SmsText): BankDiscoveryResult {
        val found = sources.flatMap { source -> source.discover(message) }
        if (found.isEmpty()) return BankDiscoveryResult.unknown()
        val merged = found.groupBy { it.institutionId }.map { (id, hits) ->
            val verifiedHit = hits.firstOrNull { it.verified && it.source.authorizesLedger() }
            val best = hits.maxBy { it.confidence }
            DiscoveredInstitution(
                institutionId = id,
                displayName = verifiedHit?.displayName ?: best.displayName,
                confidence = best.confidence,
                source = verifiedHit?.source ?: best.source,
                verified = verifiedHit != null,
                evidence = hits.flatMap { it.evidence }.distinct(),
            )
        }.sortedByDescending { it.confidence }
        val status = if (merged.size == 1) DiscoveryStatus.KNOWN else DiscoveryStatus.AMBIGUOUS
        return BankDiscoveryResult(status, merged)
    }
}
