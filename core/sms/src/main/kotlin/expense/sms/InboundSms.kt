package expense.sms

import java.time.Instant

/**
 * One inbound text, independent of how it was captured.
 * Distribution channel and Android permissions stay outside this type.
 */
data class InboundSms(
    val sender: String,
    val body: String,
    val providerMessageId: String?,
    val receivedAt: Instant,
)
