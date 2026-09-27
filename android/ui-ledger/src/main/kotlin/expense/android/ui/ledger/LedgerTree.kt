package expense.android.ui.ledger

import expense.ledger.Account
import expense.ledger.LedgerState
import expense.ledger.Transaction
import expense.parse.AccountKind

data class LedgerAccountGroup(
    val account: Account,
    val transactions: List<Transaction>,
)

data class LedgerBank(
    val institutionId: String,
    val accounts: List<LedgerAccountGroup>,
    val unassigned: List<Transaction>,
)

data class LedgerTree(
    val banks: List<LedgerBank>,
)

object LedgerTreeBuilder {
    fun build(state: LedgerState): LedgerTree {
        val knownAccounts = state.accounts.map { it.id }.toSet()
        val institutionIds = (state.accounts.map { it.institutionId } + state.transactions.map { it.institutionId })
            .distinct()
            .sorted()
        return LedgerTree(
            banks = institutionIds.map { institutionId ->
                val accounts = state.accounts
                    .filter { it.institutionId == institutionId }
                    .sortedWith(compareBy({ it.displayName.lowercase() }, { it.mask }, { it.id }))
                LedgerBank(
                    institutionId = institutionId,
                    accounts = accounts.map { account ->
                        LedgerAccountGroup(
                            account = account,
                            transactions = state.transactions
                                .filter { it.accountId == account.id }
                                .sortedWith(transactionOrder),
                        )
                    },
                    unassigned = state.transactions
                        .filter { transaction ->
                            transaction.institutionId == institutionId &&
                                (transaction.accountId == null || transaction.accountId !in knownAccounts)
                        }
                        .sortedWith(transactionOrder),
                )
            },
        )
    }

    private val transactionOrder = compareByDescending<Transaction> { it.occurredCivil }.thenBy { it.id }
}

fun Account.ledgerLabel(): String {
    val kindLabel = kind.readable()
    val name = displayName.trim()
    return if (name.isEmpty()) "$kindLabel · $mask" else "$name · $kindLabel · $mask"
}

fun AccountKind.readable(): String {
    return when (this) {
        AccountKind.DEBIT_CARD -> "Debit card"
        AccountKind.CREDIT_CARD -> "Credit card"
        AccountKind.ACCOUNT -> "Account"
        AccountKind.WALLET -> "Wallet"
        AccountKind.PREPAID -> "Prepaid"
        AccountKind.MEEZA -> "Meeza"
    }
}
