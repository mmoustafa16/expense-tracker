package expense.android.sms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class BroadcastSmsConverterTest {
    private val fallback = Instant.parse("2026-04-02T12:00:00Z")

    @Test
    fun `single segment broadcast keeps text and leaves the provider id empty`() {
        val messages = convert(
            pdus = arrayOf<Any>("only".toByteArray()),
            decode = { _, format ->
                assertEquals("3gpp", format)
                part(address = "LAB-EN", body = "Synthetic notice EGP 4", timestamp = 1_700_000_000_000L)
            },
        )

        val message = messages.single()
        assertEquals("LAB-EN", message.sender)
        assertEquals("Synthetic notice EGP 4", message.body)
        assertNull(message.providerMessageId)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), message.receivedAt)
    }

    @Test
    fun `multipart broadcast joins arabic and english in pdu order`() {
        val messages = convert(
            pdus = arrayOf<Any>("a".toByteArray(), "b".toByteArray()),
            decode = { pdu, _ ->
                when (pdu.decodeToString()) {
                    "a" -> part("معمل", "تنبيه ", 50L)
                    "b" -> part("معمل", "جنيه ٥ رسالة", 60L)
                    else -> null
                }
            },
        )

        val message = messages.single()
        assertEquals("معمل", message.sender)
        assertEquals("تنبيه جنيه ٥ رسالة", message.body)
        assertNull(message.providerMessageId)
        assertEquals(Instant.ofEpochMilli(50L), message.receivedAt)
    }

    @Test
    fun `a different sender in the same broadcast starts another message`() {
        val messages = convert(
            pdus = arrayOf<Any>("a".toByteArray(), "b".toByteArray()),
            decode = { pdu, _ ->
                when (pdu.decodeToString()) {
                    "a" -> part("LAB-EN", "Hello ", 10L)
                    "b" -> part("LAB-AR", "مرحبا", 20L)
                    else -> null
                }
            },
        )

        assertEquals(listOf("LAB-EN", "LAB-AR"), messages.map { it.sender })
        assertEquals(listOf("Hello ", "مرحبا"), messages.map { it.body })
        assertTrue(messages.all { it.providerMessageId == null })
    }

    @Test
    fun `other actions and undecodable pdus produce no messages`() {
        assertTrue(
            BroadcastSmsConverter.toInboundSms(
                action = "android.intent.action.BOOT_COMPLETED",
                pduExtra = arrayOf<Any>("a".toByteArray()),
                format = "3gpp",
                decoder = SmsPduDecoder { _, _ -> part("LAB-EN", "ignored", 1L) },
                receivedAtFallback = fallback,
            ).isEmpty(),
        )
        assertTrue(
            convert(
                pdus = arrayOf<Any>("bad".toByteArray(), 7),
                decode = { _, _ -> null },
            ).isEmpty(),
        )
        assertTrue(
            BroadcastSmsConverter.toInboundSms(
                action = SmsPermissions.SMS_RECEIVED_ACTION,
                pduExtra = null,
                format = null,
                decoder = SmsPduDecoder { _, _ -> part("LAB-EN", "ignored", 1L) },
                receivedAtFallback = fallback,
            ).isEmpty(),
        )
    }

    @Test
    fun `missing timestamp uses the supplied fallback and missing text stays empty`() {
        val message = convert(
            pdus = arrayOf<Any>("a".toByteArray()),
            decode = { _, _ -> part(address = null, body = null, timestamp = 0L) },
        ).single()

        assertEquals("", message.sender)
        assertEquals("", message.body)
        assertEquals(fallback, message.receivedAt)
        assertNull(message.providerMessageId)
    }

    private fun convert(
        pdus: Array<Any>,
        decode: (ByteArray, String?) -> DecodedSmsPart?,
    ) = BroadcastSmsConverter.toInboundSms(
        action = SmsPermissions.SMS_RECEIVED_ACTION,
        pduExtra = pdus,
        format = "3gpp",
        decoder = SmsPduDecoder { pdu, format -> decode(pdu, format) },
        receivedAtFallback = fallback,
    )

    private fun part(address: String?, body: String?, timestamp: Long) = DecodedSmsPart(
        originatingAddress = address,
        body = body,
        timestampMillis = timestamp,
    )
}
