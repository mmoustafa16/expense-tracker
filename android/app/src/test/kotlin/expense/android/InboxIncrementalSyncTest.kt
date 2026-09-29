package expense.android

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import expense.android.sms.InboxCursor
import expense.android.sms.InboxSmsConverter
import expense.android.sms.InboxSmsRow
import expense.android.storage.SqlDelightLedgerRepository
import expense.android.storage.db.ExpenseDatabase
import expense.ingest.IngestPipeline
import expense.ingest.IngestTally
import expense.ledger.LedgerState
import expense.sms.SmsPages
import expense.sms.SmsSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Incremental inbox sync, driven by the provider row id.
 *
 * The device showed an inbox whose newest messages never reached the ledger. The
 * watermark was the largest `date` already stored, and `date` comes from the
 * network: one wrong timestamp sorts below or above the rows around it, and
 * every message on the far side of it is skipped for good. The provider's own
 * row id is the only monotonic field in an inbox row, so it is the watermark
 * here, and the timestamps are used for nothing but display.
 */
class InboxIncrementalSyncTest {
    private val firstScan = Instant.parse("2026-09-20T08:00:00Z")

    /** Stands in for `content://sms/inbox`: rows in arrival order, queried as the cursor asks. */
    private class FakeInbox(rows: List<InboxSmsRow> = emptyList()) {
        private val rows = rows.toMutableList()

        fun arrive(row: InboxSmsRow) = rows.add(row)

        fun query(after: InboxCursor?): List<InboxSmsRow> {
            val floor = after?.providerMessageId ?: 0L
            return rows.filter { it.providerMessageId.toLong() > floor }
                .sortedBy { it.providerMessageId.toLong() }
        }
    }

    @Test
    fun `a second scan reads the messages that arrived after the first one`() {
        val repository = memoryRepository()
        val pipeline = IngestPipeline()
        val inbox = FakeInbox(
            listOf(
                row(1, "Debited EGP 4 for Shop", firstScan),
                row(2, "Debited EGP 6 for Store", firstScan.plusSeconds(60)),
            ),
        )
        assertNull(repository.inboxWatermark())
        assertEquals(2, sync(repository, inbox, pipeline).smsScanned)
        assertEquals(2L, repository.inboxWatermark())

        inbox.arrive(row(3, "Debited EGP 9 for Pharmacy", Instant.parse("2026-09-29T17:58:00Z")))
        inbox.arrive(row(4, "Debited EGP 11 for Grocer", Instant.parse("2026-09-30T09:10:00Z")))
        val second = sync(repository, inbox, pipeline)
        assertEquals(2, second.smsScanned)
        assertEquals(4L, repository.inboxWatermark())
        assertEquals(
            listOf("1", "2", "3", "4"),
            repository.load().messages.map { it.providerMessageId },
        )
    }

    @Test
    fun `a message stamped in the future does not hide the messages behind it`() {
        val repository = memoryRepository()
        val pipeline = IngestPipeline()
        val inbox = FakeInbox(
            listOf(
                row(1, "Debited EGP 4 for Shop", firstScan),
                row(2, "Debited EGP 6 for Store", Instant.parse("2031-01-01T00:00:00Z")),
                row(3, "Debited EGP 9 for Pharmacy", firstScan.plusSeconds(120)),
            ),
        )
        assertEquals(3, sync(repository, inbox, pipeline).smsScanned)
        assertEquals(3L, repository.inboxWatermark())

        inbox.arrive(row(4, "Debited EGP 11 for Grocer", firstScan.plusSeconds(180)))
        assertEquals(1, sync(repository, inbox, pipeline).smsScanned)
        assertEquals(
            listOf("1", "2", "3", "4"),
            repository.load().messages.map { it.providerMessageId },
        )
    }

    @Test
    fun `two messages sharing one timestamp are both stored`() {
        val repository = memoryRepository()
        val pipeline = IngestPipeline()
        val sameMoment = Instant.parse("2026-09-29T17:58:00Z")
        val inbox = FakeInbox(
            listOf(
                row(1, "Debited EGP 4 for Shop", sameMoment),
                row(2, "Debited EGP 6 for Store", sameMoment),
                row(3, "Debited EGP 9 for Pharmacy", sameMoment),
            ),
        )
        assertEquals(3, sync(repository, inbox, pipeline).smsScanned)
        assertEquals(3, repository.load().messages.size)
        assertEquals(3L, repository.inboxWatermark())
        assertEquals(0, sync(repository, inbox, pipeline).smsScanned)
    }

    @Test
    fun `a scan interrupted between pages resumes without a gap or a repeat`() {
        val repository = memoryRepository()
        val inbox = FakeInbox(
            (1..6).map { id -> row(id, "Debited EGP $id for Shop $id", firstScan.plusSeconds(id * 60L)) },
        )
        assertEquals(2, sync(repository, inbox, IngestPipeline(), pageSize = 2, maxPages = 1).smsScanned)
        assertEquals(2L, repository.inboxWatermark())

        val resumed = sync(repository, inbox, IngestPipeline(), pageSize = 2)
        assertEquals(4, resumed.smsScanned)
        assertEquals(6L, repository.inboxWatermark())
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6"),
            repository.load().messages.map { it.providerMessageId },
        )
        assertEquals(0, sync(repository, inbox, IngestPipeline(), pageSize = 2).smsScanned)
    }

    @Test
    fun `a message ingested live is not stored twice when the inbox is read later`() {
        val repository = memoryRepository()
        val pipeline = IngestPipeline()
        val body = "Debited EGP 4 for Shop"
        val live = pipeline.ingest(
            expense.sms.InboundSms(
                sender = SENDER,
                body = body,
                providerMessageId = null,
                receivedAt = firstScan,
            ),
            LedgerState.empty(),
        )
        repository.append(LedgerState.empty(), live.state)
        assertNull(repository.inboxWatermark())

        val inbox = FakeInbox(listOf(row(7, body, firstScan)))
        sync(repository, inbox, pipeline)
        val messages = repository.load().messages
        assertEquals(1, messages.size)
        assertEquals("7", messages.single().providerMessageId)
        assertEquals(7L, repository.inboxWatermark())
        assertTrue(repository.load().attempts.size == 1)
    }

    private fun sync(
        repository: SqlDelightLedgerRepository,
        inbox: FakeInbox,
        pipeline: IngestPipeline,
        pageSize: Int = SmsPages.DEFAULT_PAGE_SIZE,
        maxPages: Int = Int.MAX_VALUE,
    ): IngestTally {
        val cursor = repository.inboxWatermark()?.let(::InboxCursor)
        val source = SmsSource { InboxSmsConverter.convertAll(inbox.query(cursor)) }
        var tally = IngestTally()
        var pages = 0
        source.forEachPage(pageSize) { page ->
            if (page.isEmpty() || pages >= maxPages) return@forEachPage
            pages++
            val before = repository.loadWorkingSet()
            var working = before
            for (sms in page) {
                val result = pipeline.ingest(sms, working)
                working = result.state
                tally = tally.add(result)
            }
            repository.append(before, working)
        }
        return tally
    }

    private fun row(providerMessageId: Int, body: String, at: Instant): InboxSmsRow {
        return InboxSmsRow(
            providerMessageId = providerMessageId.toString(),
            sender = SENDER,
            body = body,
            receivedAtMillis = at.toEpochMilli(),
        )
    }

    private fun memoryRepository(): SqlDelightLedgerRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ExpenseDatabase.Schema.create(driver)
        return SqlDelightLedgerRepository(ExpenseDatabase(driver)).apply { ensureSeed() }
    }

    private companion object {
        const val SENDER = "LAB-EN"
    }
}
