package expense.parse

import expense.sms.InboundSms

sealed interface ExtractorOutcome {
    data object NoMatch : ExtractorOutcome

    data class Matched(
        val extraction: Extraction,
    ) : ExtractorOutcome

    data class Failed(
        val reason: String,
    ) : ExtractorOutcome
}

fun interface TemplateExtractor {
    fun extract(sms: InboundSms): ExtractorOutcome
}

sealed interface TemplateExecution {
    data object NoTemplateMatch : TemplateExecution

    data class ExtractorFailed(
        val template: SmsTemplate,
        val reason: String,
    ) : TemplateExecution

    data class Extracted(
        val template: SmsTemplate,
        val extraction: Extraction,
        val postable: Boolean,
    ) : TemplateExecution
}

/**
 * Runs templates in declared order. The first match or extractor failure wins.
 * Later templates are not a fallback for a low-confidence match.
 */
object TemplateRunner {
    fun execute(profile: BankProfile, sms: InboundSms): TemplateExecution {
        for (template in profile.templates) {
            val outcome = try {
                template.extractor.extract(sms)
            } catch (error: RuntimeException) {
                return TemplateExecution.ExtractorFailed(
                    template = template,
                    reason = error.message ?: error.javaClass.simpleName,
                )
            }
            when (outcome) {
                ExtractorOutcome.NoMatch -> continue
                is ExtractorOutcome.Failed -> {
                    return TemplateExecution.ExtractorFailed(template, outcome.reason)
                }
                is ExtractorOutcome.Matched -> {
                    val extraction = outcome.extraction
                    val postable = extraction.confidence >= template.minimumConfidence &&
                        extraction.candidates.isNotEmpty() &&
                        extraction.candidates.all { it.amount != null }
                    return TemplateExecution.Extracted(template, extraction, postable)
                }
            }
        }
        return TemplateExecution.NoTemplateMatch
    }
}
