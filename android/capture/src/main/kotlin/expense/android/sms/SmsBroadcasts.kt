package expense.android.sms

import android.content.Intent
import expense.sms.InboundSms
import java.time.Instant

object SmsBroadcasts {
    fun read(
        intent: Intent,
        decoder: SmsPduDecoder = AndroidSmsPduDecoder(),
        receivedAtFallback: Instant = Instant.ofEpochMilli(System.currentTimeMillis()),
    ): List<InboundSms> {
        val extras = intent.extras
        @Suppress("DEPRECATION")
        val pdus = extras?.get("pdus")
        return BroadcastSmsConverter.toInboundSms(
            action = intent.action,
            pduExtra = pdus,
            format = extras?.getString("format"),
            decoder = decoder,
            receivedAtFallback = receivedAtFallback,
        )
    }
}
