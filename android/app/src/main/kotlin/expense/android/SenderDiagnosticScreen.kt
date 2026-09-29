package expense.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import expense.android.storage.DatabaseLockedException
import expense.android.storage.LedgerSession
import expense.intelligence.DiagnosticSms
import expense.intelligence.SenderDiagnosticReport
import expense.intelligence.SenderDiscoveryDiagnostic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Temporary debug view. It shows [SenderDiagnosticReport.lines] and nothing from the SMS body.
 */
@Composable
fun SenderDiagnosticRoute(session: LedgerSession) {
    var report by remember { mutableStateOf<SenderDiagnosticReport?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            report = withContext(Dispatchers.IO) {
                val messages = session.load().messages.map { sms -> DiagnosticSms(sms.sender, sms.body) }
                SenderDiscoveryDiagnostic().summarize(messages)
            }
            message = null
        } catch (_: DatabaseLockedException) {
            message = "Unlock the ledger to continue."
        }
    }
    SenderDiagnosticScreen(report, message)
}

@Composable
fun SenderDiagnosticScreen(
    report: SenderDiagnosticReport?,
    message: String?,
) {
    if (report == null) {
        if (message != null) {
            Text(message, modifier = Modifier.padding(16.dp))
        } else {
            CircularProgressIndicator(Modifier.padding(24.dp))
        }
        return
    }
    val lines = report.lines()
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text("Temporary on-device sender summary.", style = MaterialTheme.typography.bodyMedium)
            Text("Counts and structure only.", style = MaterialTheme.typography.bodyMedium)
        }
        items(lines) { line ->
            Text(line)
        }
    }
}
