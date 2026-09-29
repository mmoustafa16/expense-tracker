package expense.android.storage

import expense.parse.ParseStatus
import java.time.Instant

data class ReviewRecord(
    val attemptId: String,
    val smsId: String,
    val status: ParseStatus,
    val sender: String,
    val receivedAt: Instant,
    val body: String?,
    val pipelineVersion: String,
    val holdReason: String? = null,
)

data class ReviewWindow(
    val rows: List<ReviewRecord>,
    val offset: Int,
    val total: Int,
)

internal fun reviewOffset(total: Int, offset: Int, pageSize: Int): Int {
    if (total <= 0) return 0
    val requested = offset.coerceAtLeast(0)
    if (requested < total) return requested
    return ((total - 1) / pageSize) * pageSize
}
