package expense.parse

/**
 * Profiles visible to the pipeline. [EMPTY] is the production registry until
 * a bank has verified positive and negative fixtures.
 */
data class BankRegistry(
    val profiles: List<BankProfile>,
) {
    init {
        val ids = profiles.map { it.id }
        require(ids.size == ids.toSet().size) { "bank profile ids must be unique" }
    }

    companion object {
        val EMPTY: BankRegistry = BankRegistry(emptyList())
    }
}
