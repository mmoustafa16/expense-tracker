package expense.ledger

import expense.money.Currency
import expense.parse.AccountKind

data class Account(
    val id: String,
    val institutionId: String,
    val kind: AccountKind,
    val mask: String,
    val currency: Currency,
)
