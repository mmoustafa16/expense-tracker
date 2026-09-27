package expense.parse

enum class TemplateLanguage {
    AR,
    EN,
    MIXED,
}

data class SmsTemplate(
    val id: String,
    val languages: Set<TemplateLanguage>,
    val minimumConfidence: Int,
    val extractor: TemplateExtractor,
) {
    init {
        require(id.isNotBlank())
        require(languages.isNotEmpty())
        require(minimumConfidence in 0..100)
    }
}
