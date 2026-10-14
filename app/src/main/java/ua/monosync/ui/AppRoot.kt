package ua.monosync.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ua.monosync.core.Currency
import ua.monosync.data.db.AccountEntity
import ua.monosync.data.db.Kind
import ua.monosync.data.db.Source
import ua.monosync.data.db.TransactionEntity
import ua.monosync.ui.components.FitText
import ua.monosync.ui.screens.AccountDialog
import ua.monosync.ui.screens.AccountsScreen
import ua.monosync.ui.screens.AnalyticsScreen
import ua.monosync.ui.screens.CategoriesScreen
import ua.monosync.ui.screens.HomeScreen
import ua.monosync.ui.screens.LogScreen
import ua.monosync.ui.screens.MonoScreen
import ua.monosync.ui.screens.NotionScreen
import ua.monosync.ui.screens.SettingsScreen
import ua.monosync.ui.screens.SubscriptionsScreen
import ua.monosync.ui.screens.TransactionSheet
import ua.monosync.ui.screens.TransactionsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab("home", "Головна", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab("transactions", "Операції", Icons.AutoMirrored.Outlined.ReceiptLong, Icons.AutoMirrored.Rounded.ReceiptLong),
    Tab("analytics", "Аналітика", Icons.Outlined.PieChart, Icons.Rounded.PieChart),
    Tab("settings", "Налаштування", Icons.Outlined.Settings, Icons.Rounded.Settings),
)

@Composable
fun AppRoot(vm: MainViewModel, deepLink: DeepLink?, onDeepLinkHandled: () -> Unit) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    val settings by vm.settings.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle()
    val listener by vm.listenerEnabled.collectAsStateWithLifecycle()
    val monoToken by vm.monoToken.collectAsStateWithLifecycle()
    val notionToken by vm.notionToken.collectAsStateWithLifecycle()
    val monoRunning by vm.monoRunning.collectAsStateWithLifecycle()
    val notionRunning by vm.notionRunning.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val subscriptions by vm.subscriptions.collectAsStateWithLifecycle()

    var openTx by remember { mutableStateOf<TransactionEntity?>(null) }
    var newTx by remember { mutableStateOf<TransactionEntity?>(null) }
    var openAccount by remember { mutableStateOf<AccountEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    LaunchedEffect(deepLink, accounts, recent) {
        when (deepLink) {
            is DeepLink.Account -> accounts.firstOrNull { it.id == deepLink.id }?.let { openAccount = it; onDeepLinkHandled() }
            is DeepLink.Transaction -> recent.firstOrNull { it.id == deepLink.id }?.let { openTx = it; onDeepLinkHandled() }
            DeepLink.Subscriptions -> { nav.navigate("subscriptions") { launchSingleTop = true }; onDeepLinkHandled() }
            null -> Unit
        }
    }

    val grantListener = {
        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        Unit
    }

    fun go(r: String) = nav.navigate(r) { launchSingleTop = true }

    val isTab = tabs.any { it.route == route }
    val categoryCurrency = settings.notionConfig?.currency?.let { Currency.byCode(it) } ?: Currency.UAH

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (isTab) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    tabs.forEach { tab ->
                        val selected = route == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(if (selected) tab.selectedIcon else tab.icon, null) },
                            label = { FitText(tab.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(route == "home" || route == "transactions", enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val acc = accounts.firstOrNull { it.id == settings.primaryAccountId && !it.hidden }
                            ?: accounts.firstOrNull { it.confirmed && !it.hidden }
                        newTx = TransactionEntity(
                            accountId = acc?.id ?: "",
                            time = System.currentTimeMillis(),
                            amount = 0,
                            currency = acc?.currency ?: Currency.UAH.iso,
                            description = "",
                            kind = Kind.EXPENSE,
                            source = Source.MANUAL,
                        )
                    },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("Додати") },
                )
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    settings = settings,
                    accounts = accounts,
                    categories = categories,
                    transactions = recent,
                    pendingCount = pending,
                    listenerEnabled = listener,
                    monoConnected = monoToken != null,
                    monoRunning = monoRunning,
                    contentPadding = padding,
                    onSync = { vm.syncMono() },
                    onGrantListener = grantListener,
                    onConfirmAccount = { openAccount = it },
                    onOpenAccount = { openAccount = it },
                    onOpenTx = { openTx = it },
                    onOpenNotion = { go("notion") },
                    onOpenMono = { go("mono") },
                    onSeeAll = { go("transactions") },
                    subscriptions = subscriptions,
                    onOpenSubscriptions = { go("subscriptions") },
                )
            }
            composable("transactions") {
                TransactionsScreen(accounts, categories, recent, settings.notionEnabled, padding) { openTx = it }
            }
            composable("analytics") {
                val month by vm.month.collectAsStateWithLifecycle()
                val monthTx by vm.monthTx.collectAsStateWithLifecycle()
                AnalyticsScreen(
                    month, monthTx, accounts, categories,
                    budgetCurrency = settings.notionConfig?.currency?.let { Currency.byCode(it) },
                    contentPadding = padding,
                    onMonth = vm::setMonth,
                )
            }
            composable("settings") {
                SettingsScreen(
                    settings = settings,
                    listenerEnabled = listener,
                    monoConnected = monoToken != null,
                    accountsCount = accounts.size,
                    contentPadding = padding,
                    onGrantListener = grantListener,
                    onMono = { go("mono") },
                    onNotion = { go("notion") },
                    onAccounts = { go("accounts") },
                    onCategories = { go("categories") },
                    onLog = { go("log") },
                    onSubscriptions = { go("subscriptions") },
                    subscriptionsCount = subscriptions.size,
                    onDynamicColor = { vm.setDynamicColor(it) },
                    onNotifyOnRecord = { vm.setNotifyOnRecord(it) },
                )
            }
            composable("mono") {
                MonoScreen(
                    settings = settings,
                    connected = monoToken != null,
                    running = monoRunning,
                    busy = busy,
                    onBack = { nav.popBackStack() },
                    onConnect = { vm.connectMono(it) },
                    onDisconnect = { vm.disconnectMono() },
                    onSync = { vm.syncMono(it) },
                    onAutoSync = { vm.setMonoAutoSync(it) },
                    onHistoryDays = { vm.setHistoryDays(it) },
                )
            }
            composable("notion") {
                val discovery by vm.discovery.collectAsStateWithLifecycle()
                NotionScreen(
                    settings = settings,
                    hasToken = notionToken != null,
                    discovery = discovery,
                    pendingCount = pending,
                    running = notionRunning,
                    busy = busy,
                    onBack = { vm.cancelDiscovery(); nav.popBackStack() },
                    onDiscover = { vm.discoverNotion(it) },
                    onRole = { role, ds -> vm.setRole(role, ds) },
                    onCurrency = { vm.setDiscoveryCurrency(it) },
                    preview = { vm.previewConfig() },
                    onSave = { vm.saveNotion { } },
                    onCancelDiscovery = { vm.cancelDiscovery() },
                    onRefreshLookups = { vm.refreshNotionLookups() },
                    onPush = { vm.pushNotion() },
                    onEnabled = { vm.setNotionEnabled(it) },
                    onIncludeTime = { vm.setNotionIncludeTime(it) },
                    onStartAt = { vm.setNotionStartAt(it) },
                    onDisconnect = { vm.disconnectNotion() },
                )
            }
            composable("accounts") {
                AccountsScreen(settings, accounts, onBack = { nav.popBackStack() }, onOpen = { openAccount = it })
            }
            composable("categories") {
                val rules by vm.rules.collectAsStateWithLifecycle()
                CategoriesScreen(
                    categories = categories,
                    rules = rules,
                    notionConnected = settings.notionConfig != null && settings.notionEnabled,
                    currency = categoryCurrency,
                    onBack = { nav.popBackStack() },
                    onDeleteRule = { vm.deleteRule(it) },
                    onCreate = { name, budget, group -> vm.createCategory(name, budget, group) },
                    onUpdate = { vm.updateCategory(it) },
                    onDelete = { vm.deleteCategory(it) },
                )
            }
            composable("subscriptions") {
                SubscriptionsScreen(
                    settings = settings,
                    subscriptions = subscriptions,
                    busy = busy,
                    onBack = { nav.popBackStack() },
                    onRemindersOn = { vm.setSubReminders(it) },
                    onRemindDays = { vm.setSubRemindDays(it) },
                    onRefresh = { vm.refreshSubscriptions() },
                    onUpdate = { vm.updateSubscription(it) },
                    onDismiss = { vm.dismissSubscription(it) },
                )
            }
            composable("log") {
                val log by vm.rawLog.collectAsStateWithLifecycle()
                LogScreen(log, onBack = { nav.popBackStack() }, onClear = { vm.clearLog() })
            }
        }
    }

    openTx?.let { tx ->
        TransactionSheet(
            initial = tx,
            isNew = false,
            accounts = accounts,
            categories = categories,
            notionEnabled = settings.notionEnabled && settings.notionConfig != null,
            onSave = { updated, categoryChanged ->
                if (categoryChanged) vm.setCategory(updated, updated.categoryId) else vm.saveTransaction(updated)
            },
            onDelete = { vm.deleteTransaction(it) },
            onResend = { vm.resend(it) },
            onMarkSubscription = { vm.markAsSubscription(it) },
            budgetCurrency = categoryCurrency,
            notionCategories = settings.notionConfig != null && settings.notionEnabled,
            onCreateCategory = { name, budget, group, done -> vm.createCategory(name, budget, group, done) },
            onDismiss = { openTx = null },
        )
    }
    newTx?.let { draft ->
        TransactionSheet(
            initial = draft,
            isNew = true,
            accounts = accounts,
            categories = categories,
            notionEnabled = false,
            onSave = { tx, _ -> vm.addManual(tx) },
            onDelete = {},
            onResend = {},
            budgetCurrency = categoryCurrency,
            notionCategories = settings.notionConfig != null && settings.notionEnabled,
            onCreateCategory = { name, budget, group, done -> vm.createCategory(name, budget, group, done) },
            onDismiss = { newTx = null },
        )
    }
    openAccount?.let { acc ->
        val fresh = accounts.firstOrNull { it.id == acc.id } ?: acc
        AccountDialog(
            account = fresh,
            allAccounts = accounts,
            notionAccounts = settings.notionAccounts,
            suggestedNotion = vm.guessNotionAccount(fresh),
            isPrimary = settings.primaryAccountId == fresh.id,
            onConfirm = { name, currency, notionId -> vm.confirmAccount(fresh, name, currency, notionId) },
            onUpdate = { vm.updateAccount(it) },
            onSetPrimary = { vm.setPrimary(fresh.id) },
            onMerge = { vm.mergeAccounts(fresh, it) },
            onDelete = { vm.deleteAccount(fresh) },
            onDismiss = { openAccount = null },
        )
    }
}
