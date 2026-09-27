package expense.ledger

import expense.categories.CategoryCatalog
import expense.categories.CategoryChange
import expense.categories.NewCategory

object LedgerCategories {
    fun add(state: LedgerState, draft: NewCategory): LedgerState {
        return state.copy(categories = CategoryCatalog.add(state.categories, draft))
    }

    fun update(state: LedgerState, id: String, change: CategoryChange): LedgerState {
        return state.copy(categories = CategoryCatalog.update(state.categories, id, change))
    }
}
