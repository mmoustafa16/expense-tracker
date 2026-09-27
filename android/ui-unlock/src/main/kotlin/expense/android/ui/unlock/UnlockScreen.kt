package expense.android.ui.unlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import expense.android.storage.BiometricUnlockPrompt
import expense.android.storage.UnlockPrompt
import expense.android.storage.UnlockResult

@Composable
fun ColdStartUnlockScreen(
    activity: FragmentActivity,
    isUnlocked: () -> Boolean,
    unlockLedger: (UnlockPrompt, (UnlockResult) -> Unit) -> Unit,
    onUnlocked: () -> Unit,
) {
    val machine = remember { ColdStartUnlock(isUnlocked, unlockLedger) }
    var phase by remember { mutableStateOf(machine.phase) }
    var failure by remember { mutableStateOf(machine.failure) }
    fun sync() {
        phase = machine.phase
        failure = machine.failure
    }
    LaunchedEffect(phase) {
        if (phase == UnlockPhase.Open) onUnlocked()
    }
    LaunchedEffect(Unit) {
        machine.start(BiometricUnlockPrompt(activity), ::sync)
    }
    UnlockContent(
        phase = phase,
        failure = failure,
        onRetry = { machine.retry(BiometricUnlockPrompt(activity), ::sync) },
    )
}

@Composable
fun UnlockContent(
    phase: UnlockPhase,
    failure: UnlockFailure?,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Expense Tracker", style = MaterialTheme.typography.headlineMedium)
        Text("Unlock the ledger on this device. Later actions stay open until you leave the app.")
        when (phase) {
            UnlockPhase.Waiting, UnlockPhase.Prompting -> CircularProgressIndicator()
            UnlockPhase.Open -> Text("Ledger open")
            UnlockPhase.Failed -> {
                Text(failure?.let(UnlockCopy::message) ?: UnlockCopy.AUTHENTICATION_FAILED)
                Button(onClick = onRetry) { Text("Try again") }
            }
        }
    }
}
