package expense.ledger

/**
 * Display name is editable. Identity stays institution + kind + mask.
 */
object AccountNames {
    fun rename(state: LedgerState, accountId: String, displayName: String): LedgerState {
        val index = state.accounts.indexOfFirst { it.id == accountId }
        require(index >= 0) { "account does not exist" }
        val current = state.accounts[index]
        val renamed = current.copy(displayName = displayName.trim())
        require(renamed.institutionId == current.institutionId)
        require(renamed.kind == current.kind)
        require(renamed.mask == current.mask)
        require(renamed.id == current.id)
        val accounts = state.accounts.toMutableList()
        accounts[index] = renamed
        return state.copy(accounts = accounts)
    }
}
