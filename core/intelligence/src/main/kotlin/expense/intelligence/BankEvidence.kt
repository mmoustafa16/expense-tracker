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
}

/**
 * Reads the sender address and the shape of the message.
 * The result is evidence only. It does not name an institution and does not post.
 * This type does not use the network.
 */
fun interface BankEvidenceCollector {
    fun collect(message: SmsText): List<MatchedEvidence>
}

/**
 * Sender-address and message-structure observations.
 * Shape and structure labels are not bank identities.
 */
class StructuralBankEvidenceCollector : BankEvidenceCollector {
    override fun collect(message: SmsText): List<MatchedEvidence> {
        val sender = message.sender.trim()
        val evidence = mutableListOf<MatchedEvidence>()
        if (sender.isEmpty()) {
            evidence += MatchedEvidence(EvidenceKind.SENDER_SHAPE, SenderShape.BLANK.name)
        } else {
            evidence += MatchedEvidence(EvidenceKind.SENDER_ALIAS, sender)
            evidence += MatchedEvidence(EvidenceKind.SENDER_SHAPE, shapeOf(sender).name)
        }
        structureOf(message.body).forEach { label ->
            evidence += MatchedEvidence(EvidenceKind.MESSAGE_STRUCTURE, label)
        }
        return evidence
    }

    private fun shapeOf(sender: String): SenderShape {
        return when {
            sender.all { it.isDigit() } && sender.length <= SHORT_CODE_LENGTH -> SenderShape.SHORT_CODE
            sender.all { it.isDigit() } -> SenderShape.LONG_NUMBER
            sender.all { it.isLetterOrDigit() || it == '-' } -> SenderShape.ALPHANUMERIC_ID
            else -> SenderShape.OTHER
        }
    }

    private fun structureOf(body: String): List<String> {
        if (body.isBlank()) return listOf(MessageStructure.EMPTY.name)
        val labels = mutableListOf(MessageStructure.PROSE.name)
        if (body.count { it == '|' } >= 2) labels += MessageStructure.PIPE_DELIMITED.name
        if (amountPresent.containsMatchIn(body)) labels += MessageStructure.AMOUNT_PRESENT.name
        return labels
    }

    private enum class SenderShape {
        BLANK,
        SHORT_CODE,
        LONG_NUMBER,
        ALPHANUMERIC_ID,
        OTHER,
    }

    private enum class MessageStructure {
        EMPTY,
        PROSE,
        PIPE_DELIMITED,
        AMOUNT_PRESENT,
    }

    private companion object {
        const val SHORT_CODE_LENGTH: Int = 6
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
