package expense.android

import expense.ingest.IngestTally
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InboxScanTest {
    @Test
    fun `a second request does not start another scan`() {
        val gate = InboxScanGate()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        gate.finish()
        assertFalse(gate.tryStart())
    }

    @Test
    fun `an abandoned scan can be started again`() {
        val gate = InboxScanGate()
        assertTrue(gate.tryStart())
        gate.abandon()
        assertTrue(gate.tryStart())
    }

    @Test
    fun `progress text is counts only`() {
        val tally = IngestTally(
            scanned = 10,
            financial = 4,
            matchedProfile = 0,
            unsupported = 4,
            parsed = 0,
            posted = 0,
        )
        val text = InboxScanText.progress(tally, running = true)
        assertTrue(text.contains("Scanned 10"))
        assertTrue(text.contains("Financial 4"))
        assertTrue(text.contains("Matched 0"))
        assertTrue(text.contains("Unsupported 4"))
        assertTrue(text.contains("Parsed 0"))
        assertTrue(text.contains("Posted 0"))
        assertEquals(
            "Financial messages with no verified bank profile stay in Review. They are not added to the ledger.",
            InboxScanText.unmatchedNote(tally),
        )
        assertNull(InboxScanText.unmatchedNote(tally.copy(posted = 1)))
        assertFalse(text.contains("EGP"))
        assertEquals("Scanning the inbox", InboxScanText.title(running = true))
        assertEquals("Last inbox scan", InboxScanText.title(running = false))
        assertEquals(
            listOf(
                ScanMetric("Scanned", "10"),
                ScanMetric("Financial", "4"),
                ScanMetric("Needs review", "4"),
            ),
            InboxScanText.metrics(tally),
        )
        assertTrue(InboxScanText.metrics(tally).none { it.label == "Posted" })
        assertEquals("10,748", InboxScanText.count(10_748))
        assertEquals("Matched 0 · Parsed 0 · Posted 0.", InboxScanText.detail(tally))
    }
}
