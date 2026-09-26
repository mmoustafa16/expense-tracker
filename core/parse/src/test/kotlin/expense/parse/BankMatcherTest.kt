package expense.parse

import expense.sms.InboundSms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class BankMatcherTest {
    @Test
    fun `production registry is empty`() {
        assertTrue(BankRegistry.EMPTY.profiles.isEmpty())
        assertTrue(BankMatcher(BankRegistry.EMPTY).match("TESTBANK").isEmpty())
    }

    @Test
    fun `sender match is exact and reports every claimant`() {
        val first = profile("example.one", "SHARED")
        val second = profile("example.two", "SHARED")
        val matcher = BankMatcher(BankRegistry(listOf(first, second)))
        assertEquals(listOf(first, second), matcher.match("SHARED"))
        assertTrue(matcher.match("OTHER").isEmpty())
    }

    private fun profile(id: String, sender: String): BankProfile {
        return BankProfile(
            id = id,
            version = "1",
            displayName = "Example",
            senderIds = setOf(sender),
            templates = emptyList(),
        )
    }
}

class DayMonthYearTest {
    @Test
    fun `parses day then month`() {
        val parsed = DayMonthYear.parse("01/02/2026 00:00")
        assertEquals(2026, parsed?.year)
        assertEquals(2, parsed?.monthValue)
        assertEquals(1, parsed?.dayOfMonth)
    }

    @Test
    fun `rejects a month that cannot be a month in day-month order`() {
        assertEquals(null, DayMonthYear.parse("02/15/2026 00:00"))
    }
}

class TemplateRunnerTest {
    @Test
    fun `first match wins and a thrown extractor becomes a failure`() {
        val profile = BankProfile(
            id = "example.runner",
            version = "1",
            displayName = "Runner",
            senderIds = setOf("RUN"),
            templates = listOf(
                SmsTemplate(
                    id = "explode",
                    languages = setOf(TemplateLanguage.EN),
                    minimumConfidence = 80,
                    extractor = TemplateExtractor { throw IllegalStateException("boom") },
                ),
                SmsTemplate(
                    id = "later",
                    languages = setOf(TemplateLanguage.EN),
                    minimumConfidence = 80,
                    extractor = TemplateExtractor { ExtractorOutcome.NoMatch },
                ),
            ),
        )
        val sms = InboundSms("RUN", "anything", null, Instant.parse("2026-01-15T08:00:00Z"))
        val execution = TemplateRunner.execute(profile, sms)
        val failed = execution as TemplateExecution.ExtractorFailed
        assertEquals("explode", failed.template.id)
        assertEquals("boom", failed.reason)
    }
}
