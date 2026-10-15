package ua.monosync.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import kotlinx.coroutines.launch
import ua.monosync.app
import ua.monosync.core.Money
import ua.monosync.data.db.AccountEntity
import ua.monosync.data.db.cur
import ua.monosync.data.db.isJar
import ua.monosync.ui.components.Group
import ua.monosync.ui.components.GroupDivider
import ua.monosync.ui.components.SectionLabel
import ua.monosync.ui.components.SettingRow
import ua.monosync.ui.theme.MonoSyncTheme

class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(RESULT_CANCELED, result)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()

        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(widgetId)
        setContent {
            val settings by app.repo.settings.flow.collectAsState(initial = null)
            MonoSyncTheme(dynamic = settings?.dynamicColor ?: true) {
                val scope = rememberCoroutineScope()
                var mode by remember { mutableStateOf(WidgetMode.BALANCE) }
                var account by remember { mutableStateOf<String?>(null) }
                var style by remember { mutableStateOf(WidgetStyle.COLOR) }
                var accounts by remember { mutableStateOf(emptyList<AccountEntity>()) }
                LaunchedEffect(Unit) {
                    accounts = app.repo.db.accounts().all().filter { it.confirmed && !it.hidden && !it.isJar }
                    val p = getAppWidgetState(this@WidgetConfigActivity, PreferencesGlanceStateDefinition, glanceId)
                    mode = WidgetMode.of(p[BalanceWidget.KEY_MODE])
                    account = p[BalanceWidget.KEY_ACCOUNT]
                    style = WidgetStyle.of(p[BalanceWidget.KEY_STYLE])
                }
                ConfigScreen(
                    mode, account, style, accounts,
                    onMode = { mode = it; if (!it.allowsAll && account == ALL_ACCOUNTS) account = null },
                    onAccount = { account = it },
                    onStyle = { style = it },
                    onDone = {
                        scope.launch {
                            updateAppWidgetState(this@WidgetConfigActivity, PreferencesGlanceStateDefinition, glanceId) {
                                it.toMutablePreferences().apply {
                                    this[BalanceWidget.KEY_MODE] = mode.name
                                    this[BalanceWidget.KEY_STYLE] = style.name
                                    this[BalanceWidget.KEY_TICK] = System.currentTimeMillis()
                                    if (account == null) remove(BalanceWidget.KEY_ACCOUNT) else this[BalanceWidget.KEY_ACCOUNT] = account!!
                                }
                            }
                            BalanceWidget().update(this@WidgetConfigActivity, glanceId)
                            setResult(RESULT_OK, result)
                            finish()
                        }
                    },
                )
            }
        }
    }
}

private val WidgetMode.icon: ImageVector
    get() = when (this) {
        WidgetMode.BALANCE -> Icons.Rounded.CreditCard
        WidgetMode.TOTAL -> Icons.Rounded.AccountBalanceWallet
        WidgetMode.MONTH -> Icons.Rounded.CalendarMonth
        WidgetMode.TODAY -> Icons.Rounded.Today
        WidgetMode.RECENT -> Icons.AutoMirrored.Rounded.ReceiptLong
        WidgetMode.CATEGORIES -> Icons.Rounded.PieChart
        WidgetMode.JARS -> Icons.Rounded.Savings
        WidgetMode.SUBSCRIPTIONS -> Icons.Rounded.Subscriptions
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(
    mode: WidgetMode,
    account: String?,
    style: WidgetStyle,
    accounts: List<AccountEntity>,
    onMode: (WidgetMode) -> Unit,
    onAccount: (String?) -> Unit,
    onStyle: (WidgetStyle) -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Віджет") }) },
        bottomBar = {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { Text("Готово") }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionLabel("Що показувати")
            Group {
                WidgetMode.entries.forEachIndexed { i, m ->
                    if (i > 0) GroupDivider()
                    SettingRow(m.icon, m.title, m.hint, trailing = { RadioButton(selected = m == mode, onClick = { onMode(m) }) }, onClick = { onMode(m) })
                }
            }

            if (mode.usesAccount && accounts.isNotEmpty()) {
                SectionLabel("Картка")
                Group {
                    val options = buildList {
                        add(null to "Основна картка")
                        if (mode.allowsAll) add(ALL_ACCOUNTS to "Усі картки")
                        accounts.forEach { add(it.id to it.name) }
                    }
                    options.forEachIndexed { i, (id, name) ->
                        if (i > 0) GroupDivider()
                        val acc = accounts.firstOrNull { it.id == id }
                        SettingRow(
                            null, name, acc?.balance?.let { Money.format(it + acc.creditLimit, acc.cur) },
                            trailing = { RadioButton(selected = account == id, onClick = { onAccount(id) }) },
                            onClick = { onAccount(id) },
                        )
                    }
                }
            }

            SectionLabel("Фон")
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WidgetStyle.entries.forEach { s ->
                    FilterChip(selected = s == style, onClick = { onStyle(s) }, label = { Text(s.title) })
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Віджет можна розтягнути від 1×1 до всього екрана: що більший розмір, то більше деталей. " +
                    "Щоб змінити вміст пізніше, утримуй віджет і натисни на олівець.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
