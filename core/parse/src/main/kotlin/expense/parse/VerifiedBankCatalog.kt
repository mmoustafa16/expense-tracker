package expense.parse

/**
 * Production bank profiles. Sender matching reads only this list.
 * A profile is a deterministic high-confidence template validator and a
 * fallback parser. It is not the only way an SMS is understood.
 *
 * Add a [BankProfile] here only after its sender ids and templates have been
 * verified against fixtures the account holder supplied. Do not guess a
 * sender id. An unknown sender stays unknown and cannot post to the ledger.
 */
object VerifiedBankCatalog {
    fun registry(): BankRegistry = BankRegistry(profiles)

    private val profiles: List<BankProfile> = emptyList()
}
