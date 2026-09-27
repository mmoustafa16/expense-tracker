package expense.parse

enum class ParseStatus {
    IGNORED_NOT_BANK,
    UNSUPPORTED,
    LOW_CONFIDENCE,
    PARSED,
    FAILED,
    AMBIGUOUS,
    ;

    fun needsReview(): Boolean = when (this) {
        UNSUPPORTED, LOW_CONFIDENCE, FAILED, AMBIGUOUS -> true
        IGNORED_NOT_BANK, PARSED -> false
    }
}
