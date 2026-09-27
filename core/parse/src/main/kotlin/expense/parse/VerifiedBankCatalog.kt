package expense.parse

/**
 * Production bank profiles. Sender matching reads only this list.
 * A financial message with no match stays in review and is not posted.
 *
 * Add a [BankProfile] here only after its sender ids and templates have been
 * verified against fixtures the account holder supplied. Do not guess a
 * sender id or a message layout, and do not parse arbitrary SMS into
 * transactions without a profile.
 */
object VerifiedBankCatalog {
    fun registry(): BankRegistry = BankRegistry(profiles)

    private val profiles: List<BankProfile> = emptyList()
}
