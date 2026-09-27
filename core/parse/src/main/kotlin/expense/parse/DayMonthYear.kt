package expense.parse

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Explicit day-month-year civil time. Templates call this when their
 * verified format uses that order. The pipeline does not scan arbitrary
 * SMS text for dates.
 */
object DayMonthYear {
    private val formatter = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")

    fun parse(text: String): LocalDateTime? {
        return try {
            LocalDateTime.parse(text.trim(), formatter)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
