package expense.ingest

import expense.parse.ParseStatus

/**
 * Aggregate ingest counts. This value never carries message text.
 */
data class IngestTally(
    val scanned: Int = 0,
    val financial: Int = 0,
    val matchedProfile: Int = 0,
    val unsupported: Int = 0,
    val parsed: Int = 0,
    val posted: Int = 0,
) {
    fun add(result: IngestResult): IngestTally {
        val nextScanned = scanned + 1
        if (result.alreadyIngested || result.status == null) {
            return copy(scanned = nextScanned)
        }
        return copy(
            scanned = nextScanned,
            financial = financial + flag(result.financial),
            matchedProfile = matchedProfile + flag(result.matchedProfile),
            unsupported = unsupported + flag(result.status == ParseStatus.UNSUPPORTED),
            parsed = parsed + flag(result.status == ParseStatus.PARSED),
            posted = posted + flag(result.posted),
        )
    }

    operator fun plus(other: IngestTally): IngestTally {
        return IngestTally(
            scanned = scanned + other.scanned,
            financial = financial + other.financial,
            matchedProfile = matchedProfile + other.matchedProfile,
            unsupported = unsupported + other.unsupported,
            parsed = parsed + other.parsed,
            posted = posted + other.posted,
        )
    }

    private fun flag(value: Boolean): Int = if (value) 1 else 0
}
