package expense.android.ui.unlock

import expense.android.storage.DatabaseKeyVault
import expense.android.storage.KeyMaterial
import expense.android.storage.LedgerSession
import expense.android.storage.UnlockPrompt
import expense.android.storage.UnlockResult
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class ColdStartUnlockTest {
    @Test
    fun `an open ledger does not prompt again`() {
        var prompts = 0
        val machine = ColdStartUnlock(
            isUnlocked = { true },
            unlockLedger = { _, _ -> prompts++ },
        )
        machine.start(UnlockPrompt { _, _ -> }) {}
        machine.start(UnlockPrompt { _, _ -> }) {}
        assertEquals(UnlockPhase.Open, machine.phase)
        assertEquals(0, prompts)
    }

    @Test
    fun `one prompt is sent and a second start does not ask again`() {
        var prompts = 0
        val machine = ColdStartUnlock(
            isUnlocked = { false },
            unlockLedger = { prompt, onResult ->
                prompts++
                prompt.authenticate(onSuccess = { onResult(UnlockResult.Ready) }, onFailure = {})
            },
        )
        machine.start(UnlockPrompt { success, _ -> success() }) {}
        machine.start(UnlockPrompt { success, _ -> success() }) {}
        assertEquals(1, prompts)
        assertEquals(UnlockPhase.Open, machine.phase)
    }

    @Test
    fun `failure can be retried and names the unchanged database`() {
        var prompts = 0
        val machine = ColdStartUnlock(
            isUnlocked = { false },
            unlockLedger = { prompt, onResult ->
                prompts++
                prompt.authenticate(onSuccess = {}, onFailure = { onResult(UnlockResult.AuthenticationFailed) })
            },
        )
        val prompt = UnlockPrompt { _, failure -> failure() }
        machine.start(prompt) {}
        machine.start(prompt) {}
        assertEquals(UnlockPhase.Failed, machine.phase)
        assertEquals(UnlockCopy.AUTHENTICATION_FAILED, UnlockCopy.message(machine.failure!!))
        assertEquals(1, prompts)
        machine.retry(prompt) {}
        assertEquals(2, prompts)
    }

    @Test
    fun `a failed unlock leaves the database file bytes unchanged`() {
        val dir = Files.createTempDirectory("unlock-failure")
        val database = File(dir.toFile(), "ledger.db")
        database.writeBytes(byteArrayOf(9, 8, 7, 6))
        val before = database.readBytes()
        var opened = false
        val session = LedgerSession(
            databaseFile = database,
            vault = DatabaseKeyVault {
                KeyMaterial.Unavailable
            },
            openDriver = { _, _ ->
                opened = true
                error("driver must not open")
            },
        )
        val machine = ColdStartUnlock(
            isUnlocked = session::isUnlocked,
            unlockLedger = session::unlock,
        )
        machine.start(UnlockPrompt { success, _ -> success() }) {}
        assertEquals(UnlockPhase.Failed, machine.phase)
        assertEquals(UnlockFailure.KeyUnavailable, machine.failure)
        assertEquals(UnlockCopy.KEY_UNAVAILABLE, UnlockCopy.message(machine.failure!!))
        assertFalse(opened)
        assertFalse(session.isUnlocked())
        assertArrayEquals(before, database.readBytes())
    }
}
