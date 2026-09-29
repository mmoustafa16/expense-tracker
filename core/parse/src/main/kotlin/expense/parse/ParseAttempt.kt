package expense.parse

data class ParseAttempt(
    val id: String,
    val smsId: String,
    val pipelineVersion: String,
    val profileId: String?,
    val profileVersion: String?,
    val templateId: String?,
    val status: ParseStatus,
    /**
     * What the pipeline judged the message to be. Stored so counts of financial
     * events can be taken from the same reading that produced the outcome,
     * rather than inferred from whether the body happened to be retained.
     */
    val eventType: FinancialEventType,
    val confidence: Int?,
    val extraction: Extraction?,
    val error: String?,
)
