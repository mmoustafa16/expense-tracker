package expense.android.sms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class InboxSmsConverterTest {
    @Test
    fun `inbox rows keep english arabic and mixed text unchanged`() {
        val receivedAt = Instant.parse("2026-03-01T08:15:00Z")
        val rows = listOf(
            row(id = "11", sender = "LAB-EN", body = "Synthetic notice EGP 4", at = receivedAt),
            row(id = "12", sender = "معمل", body = "تنبيه تجريبي جنيه ٥", at = receivedAt.plusSeconds(1)),
            row(id = "13", sender = "  LAB-MX  ", body = " Lab sample EGP 6 رسالة ", at = receivedAt.plusSeconds(2)),
        )

        val messages = InboxSmsConverter.convertAll(rows)

        assertEquals(rows.map { it.providerMessageId }, messages.map { it.providerMessageId })
        assertEquals(rows.map { it.sender }, messages.map { it.sender })
        assertEquals(rows.map { it.body }, messages.map { it.body })
        assertEquals(
            listOf(receivedAt, receivedAt.plusSeconds(1), receivedAt.plusSeconds(2)),
            messages.map { it.receivedAt },
        )
    }

    @Test
    fun `the inbox cursor asks only for rows after the stored provider row id`() {
        val cursor = InboxCursor(providerMessageId = 88)
        assertEquals("_id > ?", cursor.selection())
        assertEquals(listOf("88"), cursor.args().toList())
    }

    @Test
    fun `missing sender and body become empty strings and the provider id is kept`() {
        val message = InboxSmsConverter.convert(
            InboxSmsRow(
                providerMessageId = "7",
                sender = "",
                body = "",
                receivedAtMillis = 0L,
            ),
        )

        assertEquals("", message.sender)
        assertEquals("", message.body)
        assertEquals("7", message.providerMessageId)
        assertEquals(Instant.EPOCH, message.receivedAt)
    }

    @Test
    fun `conversion preserves input order`() {
        val messages = InboxSmsConverter.convertAll(
            listOf(
                row("1", "A", "first", Instant.EPOCH),
                row("2", "B", "second رسالة", Instant.EPOCH.plusMillis(5)),
            ),
        )

        assertEquals(listOf("first", "second رسالة"), messages.map { it.body })
    }

    @Test
    fun `inbox query reads only the columns needed for an inbound sms`() {
        assertEquals(listOf("_id", "address", "body", "date"), InboxQuery.projection.toList())
        assertEquals("_id ASC", InboxQuery.sortOrder)
    }

    private fun row(id: String, sender: String, body: String, at: Instant): InboxSmsRow {
        return InboxSmsRow(
            providerMessageId = id,
            sender = sender,
            body = body,
            receivedAtMillis = at.toEpochMilli(),
        )
    }
}
