package expense.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import expense.android.sms.SmsPermissions
import expense.android.ui.analytics.AnalyticsRoute
import expense.android.ui.ledger.AccountRoute
import expense.android.ui.ledger.CategoriesRoute
import expense.android.ui.ledger.LedgerRoute
import expense.android.ui.ledger.LedgerScanMetric
import expense.android.ui.ledger.LedgerScanSummary
import expense.android.ui.ledger.TransactionRoute
import expense.android.ui.review.ManualTransactionRoute
import expense.android.ui.review.ReviewRoute
import expense.android.ui.search.SearchRoute
import expense.android.ui.unlock.ColdStartUnlockScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

private object Routes {
    const val Unlock = "unlock"
    const val Review = "review"
    const val Manual = "review/manual/{attemptId}"
    const val Ledger = "ledger"
    const val Account = "ledger/account/{accountId}"
    const val Transaction = "ledger/transaction/{transactionId}"
    const val Categories = "ledger/categories"
    const val SenderDiagnostic = "ledger/sender-diagnostic"
    const val Search = "search"
    const val Analytics = "analytics"
    const val AnalyticsTransaction = "analytics/transaction/{transactionId}"

    fun manual(attemptId: String) = "review/manual/${android.net.Uri.encode(attemptId)}"

    fun account(accountId: String) = "ledger/account/${android.net.Uri.encode(accountId)}"

    fun transaction(transactionId: String) = "ledger/transaction/${android.net.Uri.encode(transactionId)}"

    fun analytics(transactionId: String) = "analytics/transaction/${android.net.Uri.encode(transactionId)}"
}

@Composable
fun ExpenseApp(
    application: ExpenseTrackerApplication,
    activity: FragmentActivity,
) {
    val nav = rememberNavController()
    val refresh = remember { MutableStateFlow(0) }
    val epoch by refresh.collectAsState()
    val scan by application.inboxScan.collectAsState()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        scope.launch {
            if (granted[SmsPermissions.READ_SMS] == true) {
                withContext(Dispatchers.IO) { application.scanInboxIfGranted() }
            } else {
                refresh.value = refresh.value + 1
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    withContext(Dispatchers.IO) { application.scanInboxIfGranted() }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(scan.phase, scan.tally.scanned) {
        if (scan.phase == InboxScanPhase.RUNNING || scan.phase == InboxScanPhase.FINISHED) {
            refresh.value = refresh.value + 1
        }
    }
    fun openLedger() {
        if (nav.currentDestination?.route != Routes.Unlock) return
        nav.navigate(Routes.Ledger) {
            popUpTo(Routes.Unlock) { inclusive = true }
        }
        val missing = application.missingSmsPermissions()
        if (missing.isEmpty()) {
            scope.launch {
                withContext(Dispatchers.IO) { application.scanInboxIfGranted() }
                refresh.value = refresh.value + 1
            }
        } else {
            launcher.launch(missing.toTypedArray())
        }
    }
    Scaffold(
        bottomBar = {
            if (route in TOP_LEVEL) {
                NavigationBar {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = route == destination.route,
                            onClick = {
                                nav.navigate(destination.route) {
                                    popUpTo(Routes.Ledger) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.Unlock,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            composable(Routes.Unlock) {
                ColdStartUnlockScreen(
                    activity = activity,
                    isUnlocked = { application.ledger().isUnlocked() },
                    unlockLedger = application::unlockLedger,
                    onUnlocked = { openLedger() },
                )
            }
            composable(Routes.Review) {
                ReviewRoute(
                    session = application.ledger(),
                    refreshEpoch = epoch,
                    onEnterManual = { nav.navigate(Routes.manual(it)) },
                )
            }
            composable(
                route = Routes.Manual,
                arguments = listOf(navArgument("attemptId") { type = NavType.StringType }),
            ) { entry ->
                NestedPage(title = "Manual transaction", onBack = { nav.popBackStack() }) {
                    ManualTransactionRoute(
                        session = application.ledger(),
                        attemptId = entry.arguments?.getString("attemptId").orEmpty(),
                        onPosted = {
                            refresh.value = refresh.value + 1
                            nav.popBackStack()
                        },
                    )
                }
            }
            composable(Routes.Ledger) {
                LedgerRoute(
                    session = application.ledger(),
                    refreshEpoch = epoch,
                    scan = scan.summary(),
                    showSenderDiagnostic = DeveloperTools.SENDER_DIAGNOSTIC,
                    onOpenSearch = { nav.navigate(Routes.Search) },
                    onOpenTransaction = { nav.navigate(Routes.transaction(it)) },
                    onOpenSenderDiagnostic = { nav.navigate(Routes.SenderDiagnostic) },
                )
            }
            composable(Routes.SenderDiagnostic) {
                NestedPage(title = "Sender diagnostic", onBack = { nav.popBackStack() }) {
                    SenderDiagnosticRoute(session = application.ledger())
                }
            }
            composable(
                route = Routes.Account,
                arguments = listOf(navArgument("accountId") { type = NavType.StringType }),
            ) { entry ->
                NestedPage(title = "Account", onBack = { nav.popBackStack() }) {
                    AccountRoute(
                        session = application.ledger(),
                        accountId = entry.arguments?.getString("accountId").orEmpty(),
                        onOpenTransaction = { nav.navigate(Routes.transaction(it)) },
                    )
                }
            }
            composable(
                route = Routes.Transaction,
                arguments = listOf(navArgument("transactionId") { type = NavType.StringType }),
            ) { entry ->
                val transactionId = entry.arguments?.getString("transactionId").orEmpty()
                NestedPage(title = "Transaction", onBack = { nav.popBackStack() }) {
                    TransactionRoute(
                        session = application.ledger(),
                        transactionId = transactionId,
                        onOpenAnalytics = { nav.navigate(Routes.analytics(it)) },
                        onOpenCategories = { nav.navigate(Routes.Categories) },
                    )
                }
            }
            composable(Routes.Categories) {
                NestedPage(title = "Categories", onBack = { nav.popBackStack() }) {
                    CategoriesRoute(session = application.ledger())
                }
            }
            composable(Routes.Search) {
                NestedPage(title = "Search", onBack = { nav.popBackStack() }) {
                    SearchRoute(session = application.ledger(), refreshEpoch = epoch)
                }
            }
            composable(Routes.Analytics) {
                AnalyticsRoute(
                    session = application.ledger(),
                    refreshEpoch = epoch,
                    initialTransactionId = null,
                )
            }
            composable(
                route = Routes.AnalyticsTransaction,
                arguments = listOf(navArgument("transactionId") { type = NavType.StringType }),
            ) { entry ->
                val transactionId = entry.arguments?.getString("transactionId").orEmpty()
                NestedPage(title = "Spending", onBack = { nav.popBackStack() }) {
                    AnalyticsRoute(
                        session = application.ledger(),
                        refreshEpoch = epoch,
                        initialTransactionId = transactionId,
                    )
                }
            }
        }
    }
}

private fun InboxScan.summary(): LedgerScanSummary? {
    if (phase == InboxScanPhase.IDLE) return null
    return LedgerScanSummary(
        title = InboxScanText.title(running = phase == InboxScanPhase.RUNNING),
        metrics = InboxScanText.metrics(tally).map { LedgerScanMetric(it.label, it.value) },
        detail = InboxScanText.detail(tally),
        note = InboxScanText.unmatchedNote(tally),
    )
}

@Composable
private fun NestedPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        content()
    }
}

private data class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    Destination(Routes.Ledger, "Ledger", Icons.Filled.AccountBox),
    Destination(Routes.Review, "Review", Icons.Filled.Info),
    Destination(Routes.Analytics, "Analytics", Icons.Filled.DateRange),
)

private val TOP_LEVEL = destinations.map { it.route }.toSet()

/** Sender diagnostic stays in the app for development and is not on the ledger. */
internal object DeveloperTools {
    const val SENDER_DIAGNOSTIC: Boolean = false
}
