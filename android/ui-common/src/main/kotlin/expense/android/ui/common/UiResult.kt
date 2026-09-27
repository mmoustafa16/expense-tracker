package expense.android.ui.common

sealed class UiResult<out T> {
    data class Ready<T>(val value: T) : UiResult<T>()

    data class Rejected(val message: String) : UiResult<Nothing>()
}
