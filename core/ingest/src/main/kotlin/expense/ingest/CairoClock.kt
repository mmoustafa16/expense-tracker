package expense.ingest

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object CairoClock {
    val ZONE: ZoneId = ZoneId.of("Africa/Cairo")

    fun civilFrom(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZONE)

    fun instantFrom(civil: LocalDateTime): Instant = civil.atZone(ZONE).toInstant()
}
