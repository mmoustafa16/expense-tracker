package expense.android.ui.ledger

import expense.android.storage.LedgerSession
import expense.categories.CategoryChange
import expense.categories.NewCategory
import expense.ledger.Correction
import expense.ledger.LedgerState

object LedgerSessionBindings {
    fun load(session: LedgerSession): LedgerState = session.load()

    fun tree(session: LedgerSession): LedgerTree = LedgerTreeBuilder.build(session.load())

    fun renameAccount(session: LedgerSession, accountId: String, displayName: String): LedgerState {
        return session.renameAccount(accountId, displayName)
    }

    fun correct(session: LedgerSession, correction: Correction): LedgerState = session.correct(correction)

    fun addCategory(session: LedgerSession, draft: NewCategory): LedgerState = session.addCategory(draft)

    fun updateCategory(session: LedgerSession, id: String, change: CategoryChange): LedgerState {
        return session.updateCategory(id, change)
    }
}
