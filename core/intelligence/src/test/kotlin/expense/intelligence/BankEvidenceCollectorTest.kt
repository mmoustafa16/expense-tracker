package expense.intelligence

import expense.money.Currency
import expense.money.Money
import expense.parse.FinancialEventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BankEvidenceCollectorTest {
    private val purchase = "Your card was used for EGP 450 at Talabat"
    private val collector = StructuralBankEvidenceCollector()

    @Test
    fun `the collector returns sender and structure evidence without naming a bank`() {
        val evidence = collector.collect(SmsText("TESTBANK", purchase))
        assertEquals("TESTBANK", evidence.single { it.kind == EvidenceKind.SENDER_ALIAS }.detail)
        assertEquals("ALPHANUMERIC_ID", evidence.single { it.kind == EvidenceKind.SENDER_SHAPE }.detail)
        assertTrue(evidence.any { it.kind == EvidenceKind.MESSAGE_STRUCTURE && it.detail == "AMOUNT_PRESENT" })
        assertTrue(evidence.none { it.detail.contains("450") || it.detail.contains("Talabat") || it.detail.contains(purchase) })
        assertTrue(EvidenceBackedDiscovery(collector).discover(SmsText("TESTBANK", purchase)).isEmpty())
    }

    @Test
    fun `a known verified sender resolves to that institution and each alias does too`() {
        val intelligence = FinancialSmsIntelligence.deterministic(
            listOf(
                RegisteredSender(
                    "example.test-bank",
                    "Example Test Bank",
                    setOf("TESTBANK", "TESTBANK-ALERT"),
                ),
            ),
        )
        listOf("TESTBANK", "TESTBANK-ALERT").forEach { sender ->
            val decision = intelligence.assess(SmsText(sender, purchase))
            val institution = decision.discovery.candidates.single()
            assertEquals(DiscoveryStatus.KNOWN, decision.discovery.status)
            assertEquals("example.test-bank", institution.institutionId)
            assertEquals(90, institution.confidence)
            assertEquals(DiscoverySourceKind.VERIFIED_SENDER_REGISTRY, institution.source)
            assertEquals(listOf(MatchedEvidence(EvidenceKind.SENDER_ALIAS, sender)), institution.evidence)
            assertTrue(decision.postable)
        }
    }

    @Test
    fun `an unknown sender stays unknown even when the message is a clear purchase`() {
        val intelligence = FinancialSmsIntelligence.deterministic(
            senders = listOf(RegisteredSender("example.test-bank", "Example Test Bank", setOf("TESTBANK"))),
            evidence = listOf(
                LocalInstitutionEvidence("example.evidence-bank", "Evidence Bank", setOf("EVIDENCEBANK")),
            ),
        )
        val decision = intelligence.assess(SmsText("01005551234", purchase))
        assertEquals(DiscoveryStatus.UNKNOWN, decision.discovery.status)
        assertTrue(decision.discovery.candidates.isEmpty())
        assertEquals(FinancialEventType.CARD_PURCHASE, decision.classification.eventType)
        assertEquals(Money(45000, Currency.EGP), decision.entities.amount)
        assertEquals("Talabat", decision.entities.merchant)
        assertFalse(decision.postable)
    }

    @Test
    fun `conflicting sender evidence is ambiguous and does not post`() {
        val intelligence = FinancialSmsIntelligence.deterministic(
            senders = listOf(RegisteredSender("example.bank-a", "Bank A", setOf("SHARED"))),
            evidence = listOf(
                LocalInstitutionEvidence("example.bank-a", "Bank A", setOf("SHARED")),
                LocalInstitutionEvidence("example.bank-b", "Bank B", setOf("SHARED")),
            ),
        )
        val decision = intelligence.assess(SmsText("SHARED", purchase))
        assertEquals(DiscoveryStatus.AMBIGUOUS, decision.discovery.status)
        assertEquals(
            setOf("example.bank-a", "example.bank-b"),
            decision.discovery.candidates.map { it.institutionId }.toSet(),
        )
        decision.discovery.candidates.forEach { institution ->
            assertTrue(institution.evidence.any { it.kind == EvidenceKind.SENDER_ALIAS && it.detail == "SHARED" })
            assertTrue(institution.confidence > 0)
            assertTrue(
                institution.source == DiscoverySourceKind.VERIFIED_SENDER_REGISTRY ||
                    institution.source == DiscoverySourceKind.BANK_EVIDENCE,
            )
        }
        assertNull(decision.discovery.verifiedInstitution)
        assertEquals(FinancialEventType.CARD_PURCHASE, decision.classification.eventType)
        assertEquals("ambiguous_institution", decision.reviewHold())
        assertFalse(decision.postable)
    }

    @Test
    fun `evidence can name an institution and still cannot reach the ledger`() {
        val intelligence = FinancialSmsIntelligence.deterministic(
            evidence = listOf(
                LocalInstitutionEvidence(
                    institutionId = "example.evidence-bank",
                    displayName = "Evidence Bank",
                    senderAliases = setOf("EVIDENCEBANK", "EVIDENCE-ALERT"),
                ),
            ),
        )
        listOf("EVIDENCEBANK", "EVIDENCE-ALERT").forEach { sender ->
            val decision = intelligence.assess(SmsText(sender, purchase))
            val institution = decision.discovery.candidates.single()
            assertEquals(DiscoveryStatus.KNOWN, decision.discovery.status)
            assertEquals("example.evidence-bank", institution.institutionId)
            assertEquals(60, institution.confidence)
            assertEquals(DiscoverySourceKind.BANK_EVIDENCE, institution.source)
            assertTrue(institution.evidence.any { it.kind == EvidenceKind.SENDER_ALIAS && it.detail == sender })
            assertTrue(institution.evidence.any { it.kind == EvidenceKind.SENDER_SHAPE })
            assertFalse(institution.verified)
            assertNull(decision.discovery.verifiedInstitution)
            assertEquals(FinancialEventType.CARD_PURCHASE, decision.classification.eventType)
            assertFalse(decision.postable)
        }
    }

    @Test
    fun `a sender alias without its required structure is insufficient`() {
        val intelligence = FinancialSmsIntelligence.deterministic(
            evidence = listOf(
                LocalInstitutionEvidence(
                    institutionId = "example.pipe-bank",
                    displayName = "Pipe Bank",
                    senderAliases = setOf("PIPEBANK"),
                    structureMarkers = setOf("PIPE_DELIMITED"),
                ),
            ),
        )
        val prose = intelligence.assess(SmsText("PIPEBANK", purchase))
        assertEquals(DiscoveryStatus.KNOWN, prose.discovery.status)
        assertNull(prose.discovery.verifiedInstitution)
        assertFalse(prose.postable)

        val structured = intelligence.assess(SmsText("PIPEBANK", "TB|purchase|EGP|10.00|Shop"))
        val institution = structured.discovery.candidates.single()
        assertEquals("example.pipe-bank", institution.institutionId)
        assertEquals(75, institution.confidence)
        assertTrue(institution.evidence.any { it.detail == "PIPE_DELIMITED" })
        assertFalse(institution.verified)
        assertFalse(structured.postable)
    }
}
