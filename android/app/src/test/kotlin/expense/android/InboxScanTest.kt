package expense.android

import expense.ingest.IngestTally
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InboxScanTest {
    @Test
    fun `a running sync is exclusive and a finished sync can run again`() {
        val gate = InboxScanGate()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        gate.finish()
        assertTrue(gate.tryStart())
        gate.finish()
    }

    @Test
    fun `an abandoned scan can be started again`() {
        val gate = InboxScanGate()
        assertTrue(gate.tryStart())
        gate.abandon()
        assertTrue(gate.tryStart())
    }

    @Test
    fun `the scan header names each population separately`() {
        val tally = IngestTally(
            smsScanned = 10,
            financialEvents = 6,
            postedTransactions = 3,
            reviewItems = 1,
            spendTransactions = 2,
            excludedFinancialEvents = 2,
        )
        val text = InboxScanText.progress(tally, running = true)
        assertTrue(text.contains("SMS scanned 10"))
        assertTrue(text.contains("Financial events 6"))
        assertTrue(text.contains("Posted transactions 3"))
        assertTrue(text.contains("Needs review 1"))
        assertTrue(text.contains("Spend transactions 2"))
        assertTrue(text.contains("Financial events not in the ledger 2"))
        assertFalse(text.contains("EGP"))
        assertEquals("Scanning the inbox", InboxScanText.title(running = true))
        assertEquals("Last inbox scan", InboxScanText.title(running = false))
        assertEquals(
            listOf(
                ScanMetric("SMS scanned", "10"),
                ScanMetric("Financial events", "6"),
                ScanMetric("Posted", "3"),
                ScanMetric("Needs review", "1"),
            ),
            InboxScanText.metrics(tally),
        )
        assertEquals("10,748", InboxScanText.count(10_748))
        assertEquals("Spend transactions 2 · Financial events not in the ledger 2.", InboxScanText.detail(tally))
    }

    @Test
    fun `a count of scanned messages is never presented as a count of transactions`() {
        val tally = IngestTally(smsScanned = 2344, financialEvents = 1809, postedTransactions = 1200)
        assertTrue(InboxScanText.metrics(tally).none { it.label.contains("transaction", ignoreCase = true) })
        val scanned = InboxScanText.metrics(tally).single { it.label == "SMS scanned" }
        val events = InboxScanText.metrics(tally).single { it.label == "Financial events" }
        val posted = InboxScanText.metrics(tally).single { it.label == "Posted" }
        assertEquals(listOf("2,344", "1,809", "1,200"), listOf(scanned.value, events.value, posted.value))
    }

    @Test
    fun `review items are explained only while nothing has posted`() {
        val held = IngestTally(smsScanned = 4, financialEvents = 4, reviewItems = 4)
        assertEquals(
            "Financial messages from a sender this device has not verified stay in Review. " +
                "They are not added to the ledger.",
            InboxScanText.unmatchedNote(held),
        )
        assertNull(InboxScanText.unmatchedNote(held.copy(postedTransactions = 1)))
        assertNull(InboxScanText.unmatchedNote(held.copy(reviewItems = 0)))
    }
}
