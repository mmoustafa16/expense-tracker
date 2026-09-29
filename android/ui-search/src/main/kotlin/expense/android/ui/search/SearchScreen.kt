package expense.android.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
    var loadedQuery by remember { mutableStateOf("") }
    var offset by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(SearchPage(emptyList(), 0, 0)) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query, offset, refreshEpoch) {
        val start = if (query != loadedQuery) 0 else offset
        loadedQuery = query
        searching = true
        try {
            val loaded = withContext(Dispatchers.IO) { SearchSession.page(session, query, start) }
            page = loaded
            if (offset != loaded.offset) offset = loaded.offset
            message = null
        } catch (_: DatabaseLockedException) {
            message = "Unlock the ledger to continue."
        }
        searching = false
    }
    SearchScreen(
        query = query,
        page = page,
        searching = searching,
        message = message,
        onQuery = { query = it },
        onPrevious = { offset = (page.offset - SearchPages.PAGE_SIZE).coerceAtLeast(0) },
        onNext = { offset = page.offset + page.hits.size },
    )
}

@Composable
fun SearchScreen(
    query: String,
    page: SearchPage,
    searching: Boolean,
    message: String?,
    onQuery: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
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
            if (query.isNotBlank() && !searching && page.hits.isEmpty() && message == null) {
                Text("No matches.")
            }
            if (page.total > SearchPages.PAGE_SIZE) {
                val end = page.offset + page.hits.size
                Text("Showing ${page.offset + 1}–$end of ${page.total}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPrevious, enabled = page.offset > 0) { Text("Previous") }
                    TextButton(onClick = onNext, enabled = end < page.total) { Text("Next") }
                }
            }
        }
        items(items = page.hits) { hit ->
            ListItem(
                headlineContent = { Text(hit.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(hit.subtitle, maxLines = 4, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}
