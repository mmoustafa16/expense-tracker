package expense.android.sms

import expense.ingest.IdGenerator
import expense.ingest.IngestPipeline
import expense.parse.ParseStatus
import expense.sms.SmsSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class PipelineSmsSinkTest {
    @Test
    fun `inbox messages in three scripts go through the existing pipeline unchanged`() {
        val receivedAt = Instant.parse("2026-05-01T09:00:00Z")
        val sink = sink()
        sink.ingest(
            SmsSource {
                InboxSmsConverter.convertAll(
                    listOf(
                        InboxSmsRow("11", "  LAB-EN  ", "Debited EGP 4 for Shop", receivedAt.toEpochMilli()),
                        InboxSmsRow("12", "معمل", "تم خصم ٥ جنيه", receivedAt.plusSeconds(60).toEpochMilli()),
                        InboxSmsRow("13", "LAB-MX", "Debited EGP 6 for Store", receivedAt.plusSeconds(120).toEpochMilli()),
                        InboxSmsRow("14", "LAB-CHAT", "hello مرحبا", receivedAt.plusSeconds(180).toEpochMilli()),
                    ),
                )
            },
        )

        val state = sink.ledgerState()
        assertEquals(listOf("LAB-EN", "معمل", "LAB-MX", "LAB-CHAT"), state.messages.map { it.sender })
        assertEquals(
            listOf("Debited EGP 4 for Shop", "تم خصم ٥ جنيه", "Debited EGP 6 for Store", null),
            state.messages.map { it.body },
        )
        assertEquals(listOf("11", "12", "13", "14"), state.messages.map { it.providerMessageId })
        assertEquals(
            listOf(
                ParseStatus.UNSUPPORTED,
                ParseStatus.UNSUPPORTED,
                ParseStatus.UNSUPPORTED,
                ParseStatus.IGNORED_NOT_BANK,
            ),
            state.attempts.map { it.status },
        )
        assertTrue(state.transactions.isEmpty())
        assertTrue(state.accounts.isEmpty())
    }

    @Test
    fun `scanning the same inbox ids again does not store a second copy`() {
        val receivedAt = Instant.parse("2026-05-01T09:00:00Z")
        val rows = listOf(InboxSmsRow("11", "LAB-EN", "Debited EGP 4 for Shop", receivedAt.toEpochMilli()))
        val sink = sink()
        sink.ingest(SmsSource { InboxSmsConverter.convertAll(rows) })
        sink.ingest(SmsSource { InboxSmsConverter.convertAll(rows) })

        assertEquals(1, sink.ledgerState().messages.size)
        assertEquals("Debited EGP 4 for Shop", sink.ledgerState().messages.single().body)
    }

    @Test
    fun `a broadcast and a later inbox row for the same text dedupe inside the replay window`() {
        val receivedAt = Instant.parse("2026-05-01T09:00:00Z")
        val sink = sink()
        val broadcast = BroadcastSmsConverter.joinParts(
            listOf(DecodedSmsPart("LAB-EN", "Debited EGP 4 for Shop", receivedAt.toEpochMilli())),
            receivedAtFallback = receivedAt,
        )
        sink.accept(broadcast)
        sink.ingest(
            SmsSource {
                InboxSmsConverter.convertAll(
                    listOf(
                        InboxSmsRow(
                            providerMessageId = "11",
                            sender = "LAB-EN",
                            body = "Debited EGP 4 for Shop",
                            receivedAtMillis = receivedAt.plusSeconds(30).toEpochMilli(),
                        ),
                    ),
                )
            },
        )

        val messages = sink.ledgerState().messages
        assertEquals(2, messages.size)
        assertNull(messages[0].providerMessageId)
        assertEquals("11", messages[1].providerMessageId)
        assertEquals("Debited EGP 4 for Shop", messages[0].body)
        assertEquals(1, sink.ledgerState().attempts.size)
        assertTrue(sink.ledgerState().transactions.isEmpty())
    }

    @Test
    fun `an empty broadcast does not change the ledger`() {
        val sink = sink()
        sink.accept(emptyList())
        assertTrue(sink.ledgerState().messages.isEmpty())
    }

    private fun sink(): PipelineSmsSink {
        var next = 0
        val ids = IdGenerator { "id-${next++}" }
        return PipelineSmsSink(pipeline = IngestPipeline(ids = ids))
    }
}
