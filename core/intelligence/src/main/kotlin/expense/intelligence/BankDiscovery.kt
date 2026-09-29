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
    INSTITUTIONAL_SENDER,
    ;

    /**
     * A ledger post needs one authorizing source.
     * Evidence, a user confirmation, a model hint, and a learned pattern can name
     * a candidate. They cannot authorize the post by themselves.
     */
    fun authorizesLedger(): Boolean = when (this) {
        VERIFIED_SENDER_REGISTRY, PUBLIC_BANK_METADATA, INSTITUTIONAL_SENDER -> true
        BANK_EVIDENCE, USER_CONFIRMED_SENDER, ON_DEVICE_MODEL, LOCAL_LEARNED_PATTERN -> false
    }
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
 * Exact sender match against [InstitutionCatalog] or an injected record list.
 * A match is data. It does not parse the SMS. Nothing is fetched.
 */
class PublicBankMetadata(
    private val records: List<RegisteredSender> = InstitutionCatalog.bundled(),
) : BankDiscoverySource {
    override val kind: DiscoverySourceKind = DiscoverySourceKind.PUBLIC_BANK_METADATA

    override fun discover(message: SmsText): List<DiscoveredInstitution> {
        val trimmed = message.sender.trim()
        if (trimmed.isEmpty()) return emptyList()
        return records.filter { record -> record.senderIds.any { it.trim() == trimmed } }.map { record ->
            DiscoveredInstitution(
                institutionId = record.institutionId,
                displayName = record.displayName,
                confidence = METADATA_CONFIDENCE,
                source = kind,
                verified = true,
                evidence = listOf(MatchedEvidence(EvidenceKind.SENDER_ALIAS, trimmed)),
            )
        }
    }

    private companion object {
        const val METADATA_CONFIDENCE: Int = 90
    }
}

/**
 * Names the sender channel and decides whether its history has earned the right
 * to post.
 *
 * An alphanumeric address is a channel, not an institution. The channel becomes
 * an institution only when [InstitutionEvidencePolicy] is satisfied by what this
 * device has already seen from that exact address: repeated completed money
 * movements, in more than one form or with consistent instrument masks, and more
 * financial traffic than not. A hospital, a restaurant, a delivery service, and
 * a marketing channel never clear that bar, and no list of their names is
 * needed to keep them out.
 *
 * A handset number, a blank sender, and a one- or two-character token produce no
 * institution. This type does not read a bank list and does not read the body.
 */
class InstitutionalSenderDiscovery(
    private val evidence: SenderEvidenceSource = SenderEvidenceSource.EMPTY,
    private val policy: InstitutionEvidencePolicy = InstitutionEvidencePolicy.DEFAULT,
) : BankDiscoverySource {
    override val kind: DiscoverySourceKind = DiscoverySourceKind.INSTITUTIONAL_SENDER

    override fun discover(message: SmsText): List<DiscoveredInstitution> {
        val sender = message.sender.trim()
        if (!isInstitutionalChannel(sender)) return emptyList()
        val id = senderInstitutionId(sender)
        if (id.length < MIN_ID_LENGTH) return emptyList()
        val history = evidence.evidenceFor(sender)
        val verified = history != null && policy.verifies(history)
        return listOf(
            DiscoveredInstitution(
                institutionId = id,
                displayName = sender,
                confidence = if (verified) CHANNEL_CONFIDENCE else CHANNEL_CANDIDATE,
                source = kind,
                verified = verified,
                evidence = buildList {
                    add(MatchedEvidence(EvidenceKind.SENDER_ALIAS, sender))
                    add(MatchedEvidence(EvidenceKind.SENDER_SHAPE, senderAddressShape(sender).name))
                    if (history != null) {
                        add(MatchedEvidence(EvidenceKind.SENDER_HISTORY, historyLabel(history)))
                    }
                },
            ),
        )
    }

    /** A closed summary of the counts. It carries no message text. */
    private fun historyLabel(history: SenderEvidence): String {
        return "events=${history.financialEvents} movements=${history.movementEvents} " +
            "forms=${history.movementEventTypes} masks=${history.instrumentMasks.size}"
    }

    private companion object {
        const val CHANNEL_CONFIDENCE: Int = 90
        const val CHANNEL_CANDIDATE: Int = 40
        const val MIN_ID_LENGTH: Int = 3
    }
}

/**
 * A dedicated sender channel is an institution. A person's phone number is not.
 */
fun isInstitutionalChannel(sender: String): Boolean {
    val trimmed = sender.trim()
    return when (senderAddressShape(trimmed)) {
        SenderAddressShape.ALPHANUMERIC_ID ->
            trimmed.length in ALPHANUMERIC_LENGTH && trimmed.any { it.isLetter() }
        SenderAddressShape.SHORT_CODE -> trimmed.length in SHORT_CODE_CHANNEL
        SenderAddressShape.BLANK, SenderAddressShape.LONG_NUMBER, SenderAddressShape.OTHER -> false
    }
}

/** Stable id shared by the same address in any letter case. Not a legal name. */
fun senderInstitutionId(sender: String): String {
    return sender.trim().lowercase().filter { it.isLetterOrDigit() }
}

private val ALPHANUMERIC_LENGTH: IntRange = 3..32
private val SHORT_CODE_CHANNEL: IntRange = 4..6

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
 *
 * A registry record, public metadata, or another explicit claim wins over the
 * sender-channel fallback. The fallback is used only when nothing else names
 * this sender. Two different claims stay ambiguous and cannot post.
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
        val claimed = merged.filterNot { it.source == DiscoverySourceKind.INSTITUTIONAL_SENDER }
        val chosen = if (claimed.isNotEmpty()) claimed else merged
        val status = if (chosen.size == 1) DiscoveryStatus.KNOWN else DiscoveryStatus.AMBIGUOUS
        return BankDiscoveryResult(status, chosen)
    }
}
