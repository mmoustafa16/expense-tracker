package expense.android.ui.unlock

import expense.android.storage.UnlockPrompt
import expense.android.storage.UnlockResult

enum class UnlockPhase {
    Waiting,
    Prompting,
    Open,
    Failed,
}

enum class UnlockFailure {
    AuthenticationFailed,
    KeyUnavailable,
}

object UnlockCopy {
    const val AUTHENTICATION_FAILED: String = "Unlock failed. The ledger database was left unchanged."
    const val KEY_UNAVAILABLE: String = "The ledger key is unavailable. The ledger database was left unchanged."

    fun message(failure: UnlockFailure): String {
        return when (failure) {
            UnlockFailure.AuthenticationFailed -> AUTHENTICATION_FAILED
            UnlockFailure.KeyUnavailable -> KEY_UNAVAILABLE
        }
    }
}

/**
 * One prompt per cold start. A failed prompt does not ask again until the user
 * retries, and it does not delete or replace the database file.
 */
class ColdStartUnlock(
    private val isUnlocked: () -> Boolean,
    private val unlockLedger: (UnlockPrompt, (UnlockResult) -> Unit) -> Unit,
) {
    var phase: UnlockPhase = UnlockPhase.Waiting
        private set

    var failure: UnlockFailure? = null
        private set

    private var promptSent = false

    fun start(prompt: UnlockPrompt, onChange: () -> Unit) {
        if (isUnlocked()) {
            phase = UnlockPhase.Open
            failure = null
            onChange()
            return
        }
        if (promptSent || phase == UnlockPhase.Prompting) return
        request(prompt, onChange)
    }

    fun retry(prompt: UnlockPrompt, onChange: () -> Unit) {
        if (phase == UnlockPhase.Prompting) return
        promptSent = false
        request(prompt, onChange)
    }

    private fun request(prompt: UnlockPrompt, onChange: () -> Unit) {
        promptSent = true
        phase = UnlockPhase.Prompting
        failure = null
        onChange()
        unlockLedger(prompt) { result ->
            when (result) {
                UnlockResult.Ready -> {
                    phase = UnlockPhase.Open
                    failure = null
                }
                UnlockResult.AuthenticationFailed -> {
                    phase = UnlockPhase.Failed
                    failure = UnlockFailure.AuthenticationFailed
                }
                UnlockResult.KeyUnavailable -> {
                    phase = UnlockPhase.Failed
                    failure = UnlockFailure.KeyUnavailable
                }
            }
            onChange()
        }
    }
}
