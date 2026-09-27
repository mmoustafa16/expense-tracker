package expense.android.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SearchRoute(
    session: LedgerSession,
    refreshEpoch: Int,
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHitView>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query, refreshEpoch) {
        searching = true
        try {
            hits = withContext(Dispatchers.IO) { SearchSession.query(session, query) }
            message = null
        } catch (_: DatabaseLockedException) {
            message = "Unlock the ledger to continue."
        }
        searching = false
    }
    SearchScreen(query, hits, searching, message) { query = it }
}

@Composable
fun SearchScreen(
    query: String,
    hits: List<SearchHitView>,
    searching: Boolean,
    message: String?,
    onQuery: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Search", style = MaterialTheme.typography.headlineSmall)
        Text("Matches come from the ledger on this device.")
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Message, merchant, reference, amount, or category") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (message != null) Text(message)
        if (searching) CircularProgressIndicator()
        if (query.isNotBlank() && !searching && hits.isEmpty() && message == null) {
            Text("No matches.")
        }
        hits.forEach { hit ->
            ListItem(
                headlineContent = { Text(hit.title) },
                supportingContent = { Text(hit.subtitle) },
            )
        }
    }
}
