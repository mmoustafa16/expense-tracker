package expense.parse

/**
 * Optional template profiles. The intelligence pipeline understands an SMS
 * without one of these. A profile is a high-confidence template check, not
 * a required parser and not a special case for one institution.
 *
 * Sender authorization for institutions that have no template lives in
 * the intelligence bootstrap records. Add a sender alias only after it was
 * copied from the device. Do not guess one. An unknown sender stays unknown
 * and cannot post to the ledger.
 */
object VerifiedBankCatalog {
    fun registry(): BankRegistry = BankRegistry(profiles)

    private val profiles: List<BankProfile> = emptyList()
}
