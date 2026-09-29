package expense.intelligence

/**
 * A local observation about a message. Evidence never authorizes a ledger post
 * and never includes the message body, an amount, or an account number.
 */
data class MatchedEvidence(
    val kind: EvidenceKind,
    val detail: String,
)

enum class EvidenceKind {
    SENDER_ALIAS,
    SENDER_SHAPE,
    MESSAGE_STRUCTURE,

    /** Aggregate counts observed from one sender. Never message text. */
    SENDER_HISTORY,
}

/**
 * Reads the sender address and the shape of the message.
 * The result is evidence only. It does not name an institution and does not post.
 * This type does not use the network.
 */
fun interface BankEvidenceCollector {
    fun collect(message: SmsText): List<MatchedEvidence>
}

enum class SenderAddressShape {
    BLANK,
    SHORT_CODE,
    LONG_NUMBER,
    ALPHANUMERIC_ID,
    OTHER,
    ;

    /** Closed label for the on-device sender diagnostic. */
    fun diagnosticLabel(): String = when (this) {
        SHORT_CODE -> "short code"
        LONG_NUMBER -> "long numeric"
        ALPHANUMERIC_ID -> "alphanumeric"
        BLANK, OTHER -> "other"
    }
}

/**
 * Address shape only. This does not read the message body.
 *
 * Operators hand out alphanumeric sender ids containing spaces, dots, and
 * ampersands as readily as bare tokens, so an address like `SAIB Bank` is the
 * same kind of channel as `SAIB`. Treating the space as unrecognizable is what
 * made such senders unidentifiable, and nothing about the separator changes who
 * may post: that is still decided by accumulated evidence.
 */
fun senderAddressShape(sender: String): SenderAddressShape {
    val trimmed = sender.trim()
    if (trimmed.isEmpty()) return SenderAddressShape.BLANK
    val digitsOnly = trimmed.all { it.isDigit() || it == '+' }
    return when {
        digitsOnly && trimmed.length <= SHORT_CODE_LENGTH -> SenderAddressShape.SHORT_CODE
        digitsOnly -> SenderAddressShape.LONG_NUMBER
        trimmed.all { it.isLetterOrDigit() || it in ALPHANUMERIC_PUNCTUATION } -> SenderAddressShape.ALPHANUMERIC_ID
        else -> SenderAddressShape.OTHER
    }
}

private const val SHORT_CODE_LENGTH: Int = 6
private val ALPHANUMERIC_PUNCTUATION: Set<Char> = setOf('-', '_', '.', '&', ' ', '\'')

/**
 * Sender-address and message-structure observations.
 * Shape and structure labels are not bank identities.
 */
class StructuralBankEvidenceCollector : BankEvidenceCollector {
    override fun collect(message: SmsText): List<MatchedEvidence> {
        val sender = message.sender.trim()
        val shape = senderAddressShape(sender)
        val evidence = mutableListOf<MatchedEvidence>()
        if (shape != SenderAddressShape.BLANK) {
            evidence += MatchedEvidence(EvidenceKind.SENDER_ALIAS, sender)
        }
        evidence += MatchedEvidence(EvidenceKind.SENDER_SHAPE, shape.name)
        structureOf(message.body).forEach { label ->
            evidence += MatchedEvidence(EvidenceKind.MESSAGE_STRUCTURE, label)
        }
        return evidence
    }

    private fun structureOf(body: String): List<String> {
        if (body.isBlank()) return listOf(MessageStructure.EMPTY.name)
        val labels = mutableListOf(MessageStructure.PROSE.name)
        if (body.count { it == '|' } >= 2) labels += MessageStructure.PIPE_DELIMITED.name
        if (amountPresent.containsMatchIn(body)) labels += MessageStructure.AMOUNT_PRESENT.name
        return labels
    }

    private enum class MessageStructure {
        EMPTY,
        PROSE,
        PIPE_DELIMITED,
        AMOUNT_PRESENT,
    }

    private companion object {
        val amountPresent = Regex("""(?i)\b(EGP|USD|EUR|GBP|LE)\b|جنيه|دولار|يورو""")
    }
}

/**
 * Locally verified evidence for one institution.
 * [senderAliases] are exact sender ids from a fixture. Do not put a real bank
 * sender here unless that fixture exists. Empty [structureMarkers] means the
 * alias alone is enough to suggest the institution. A suggestion still cannot post.
 */
data class LocalInstitutionEvidence(
    val institutionId: String,
    val displayName: String,
    val senderAliases: Set<String>,
    val structureMarkers: Set<String> = emptySet(),
)

/**
 * Turns collected evidence into institution candidates.
 * Candidates stay unverified. [VerifiedSenderRegistry] is the ledger authority.
 * An empty catalog discovers nobody, even when the collector saw a sender.
 */
class EvidenceBackedDiscovery(
    private val collector: BankEvidenceCollector,
    private val catalog: List<LocalInstitutionEvidence> = emptyList(),
) : BankDiscoverySource {
    override val kind: DiscoverySourceKind = DiscoverySourceKind.BANK_EVIDENCE

    override fun discover(message: SmsText): List<DiscoveredInstitution> {
        if (catalog.isEmpty()) return emptyList()
        val evidence = collector.collect(message)
        val sender = message.sender.trim()
        if (sender.isEmpty()) return emptyList()
        return catalog.filter { profile -> matches(profile, sender, evidence) }.map { profile ->
            val matched = evidence.filter { item ->
                item.kind != EvidenceKind.SENDER_ALIAS || item.detail == sender
            }
            DiscoveredInstitution(
                institutionId = profile.institutionId,
                displayName = profile.displayName,
                confidence = if (profile.structureMarkers.isEmpty()) ALIAS_CONFIDENCE else STRUCTURE_CONFIDENCE,
                source = kind,
                verified = false,
                evidence = matched,
            )
        }
    }

    private fun matches(
        profile: LocalInstitutionEvidence,
        sender: String,
        evidence: List<MatchedEvidence>,
    ): Boolean {
        if (profile.senderAliases.none { it.trim() == sender }) return false
        if (profile.structureMarkers.isEmpty()) return true
        val present = evidence.filter { it.kind == EvidenceKind.MESSAGE_STRUCTURE }.map { it.detail }.toSet()
        return profile.structureMarkers.all { it in present }
    }

    private companion object {
        const val ALIAS_CONFIDENCE: Int = 60
        const val STRUCTURE_CONFIDENCE: Int = 75
    }
}
