package expense.parse

/**
 * One versioned bank definition. The pipeline never branches on [id].
 * Adding a bank means registering another instance, not editing the core.
 */
data class BankProfile(
    val id: String,
    val version: String,
    val displayName: String,
    val senderIds: Set<String>,
    val templates: List<SmsTemplate>,
) {
    init {
        require(id.isNotBlank())
        require(version.isNotBlank())
        require(displayName.isNotBlank())
    }

    fun accepts(sender: String): Boolean {
        val trimmed = sender.trim()
        return senderIds.any { it.trim() == trimmed }
    }
}
