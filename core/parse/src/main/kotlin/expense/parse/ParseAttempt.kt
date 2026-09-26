package expense.parse

data class ParseAttempt(
    val id: String,
    val smsId: String,
    val pipelineVersion: String,
    val profileId: String?,
    val profileVersion: String?,
    val templateId: String?,
    val status: ParseStatus,
    val confidence: Int?,
    val extraction: Extraction?,
    val error: String?,
)
