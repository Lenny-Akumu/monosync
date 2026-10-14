package ua.monosync.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ua.monosync.data.AppSettings
import ua.monosync.data.NotionRole
import ua.monosync.data.remote.NotionDataSource
import ua.monosync.ui.Busy
import ua.monosync.ui.NotionDiscovery
import ua.monosync.ui.components.Group
import ua.monosync.ui.components.GroupDivider
import ua.monosync.ui.components.NotionLogo
import ua.monosync.ui.components.SectionLabel
import ua.monosync.ui.components.SelectDialog
import ua.monosync.ui.components.SettingRow
import ua.monosync.ui.components.dateTimeOf
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(title: String, onBack: () -> Unit, busy: Busy = Busy.Idle, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
            actions = {
                if (busy is Busy.Working) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(22.dp), strokeWidth = 2.5.dp)
            },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

@Composable
fun Hint(text: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

@Composable
fun SecretField(value: String, onValue: (String) -> Unit, label: String) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.Lock, null) },
        trailingIcon = {
            IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Показати") }
        },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun MonoScreen(
    settings: AppSettings,
    connected: Boolean,
    running: Boolean,
    busy: Busy,
    onBack: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onSync: (Boolean) -> Unit,
    onAutoSync: (Boolean) -> Unit,
    onHistoryDays: (Int) -> Unit,
) {
    val context = LocalContext.current
    var token by rememberSaveable { mutableStateOf("") }
    var pickDays by remember { mutableStateOf(false) }
    SubScreen("Monobank API", onBack, busy) {
        if (!connected) {
            Hint(
                "Особистий токен дає MonoSync доступ лише на читання: баланси, виписку та MCC-коди. " +
                    "Переказати гроші з ним неможливо. Токен зберігається зашифрованим лише на цьому телефоні."
            )
            Spacer(Modifier.height(12.dp))
            Group {
                SettingRow(
                    Icons.AutoMirrored.Rounded.OpenInNew, "1. Отримай токен",
                    "Відкрий api.monobank.ua, авторизуйся через QR у застосунку Mono й скопіюй токен",
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.monobank.ua/"))) } },
                )
            }
            Spacer(Modifier.height(12.dp))
            SecretField(token, { token = it }, "2. Встав токен сюди")
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onConnect(token) },
                enabled = token.length > 20 && busy is Busy.Idle,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Підключити") }
            Text(
                "Без токена MonoSync теж працює, але лише зі сповіщень: без MCC-категорій та без історії.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            Group {
                SettingRow(
                    Icons.Rounded.AccountBalance,
                    settings.monoClientName ?: "Підключено",
                    when {
                        settings.monoLastError != null -> "Остання помилка: ${settings.monoLastError}"
                        settings.monoLastSync > 0 -> "Остання синхронізація: ${dateTimeOf(settings.monoLastSync)}"
                        else -> "Ще не синхронізовано"
                    },
                )
                GroupDivider()
                SettingRow(
                    Icons.Rounded.Sync, "Синхронізувати зараз",
                    if (running) "Триває… Банк дозволяє 1 запит на хвилину для кожної картки" else "Баланси та нові операції",
                    trailing = { if (running) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp) },
                    onClick = { onSync(false) },
                )
                GroupDivider()
                SettingRow(
                    Icons.Rounded.History, "Завантажити історію",
                    "За останні ${settings.historyDays} дн. Старіші за дату старту Notion в Notion не підуть",
                    trailing = { TextButton(onClick = { pickDays = true }) { Text("${settings.historyDays} дн.") } },
                    onClick = { onSync(true) },
                )
                GroupDivider()
                SettingRow(
                    Icons.Rounded.Schedule, "Фонова звірка", "Кожні 30 хвилин і через півтори хвилини після кожного сповіщення",
                    trailing = { Switch(checked = settings.monoAutoSync, onCheckedChange = onAutoSync) },
                    onClick = { onAutoSync(!settings.monoAutoSync) },
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Відключити токен") }
        }
    }
    if (pickDays) {
        SelectDialog(
            title = "Скільки днів історії",
            options = listOf(1, 3, 7, 14, 31),
            selected = settings.historyDays,
            label = { "$it дн." },
            onSelect = onHistoryDays,
            onDismiss = { pickDays = false },
        )
    }
}

@Composable
fun NotionScreen(
    settings: AppSettings,
    hasToken: Boolean,
    discovery: NotionDiscovery?,
    pendingCount: Int,
    running: Boolean,
    busy: Busy,
    onBack: () -> Unit,
    onDiscover: (String) -> Unit,
    onRole: (NotionRole, NotionDataSource?) -> Unit,
    onCurrency: (String?) -> Unit,
    preview: () -> ua.monosync.data.NotionConfig?,
    onSave: () -> Unit,
    onCancelDiscovery: () -> Unit,
    onRefreshLookups: () -> Unit,
    onPush: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onIncludeTime: (Boolean) -> Unit,
    onStartAt: (Long) -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    var token by rememberSaveable { mutableStateOf("") }
    var pickRole by remember { mutableStateOf<NotionRole?>(null) }
    var pickCurrency by remember { mutableStateOf(false) }
    var pickStart by remember { mutableStateOf(false) }
    val config = settings.notionConfig

    SubScreen("Notion", onBack, busy) {
        when {
            discovery != null -> {
                Hint("Я знайшов бази й спробував сам зрозуміти, яка для чого. Перевір і виправ, якщо треба.")
                SectionLabel("Бази")
                Group {
                    NotionRole.entries.forEachIndexed { i, role ->
                        if (i > 0) GroupDivider()
                        SettingRow(
                            Icons.Rounded.TableChart, role.title,
                            discovery.roles[role]?.title ?: "Не вибрано",
                            trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                            onClick = { pickRole = role },
                        )
                    }
                }
                val p = preview()
                SectionLabel("Валюта сум у Notion")
                Group {
                    SettingRow(
                        Icons.Rounded.CurrencyExchange, p?.currency ?: "—",
                        if (discovery.currency == null) "Визначено за форматом колонки суми. Інші валюти конвертуються за курсом Mono" else "Вибрано вручну",
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                        onClick = { pickCurrency = true },
                    )
                }
                if (p != null) {
                    SectionLabel("Що куди писатиму")
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOfNotNull(p.expenses, p.incomes, p.transfers).forEach { t ->
                                Text(t.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOfNotNull(
                                        "назва → ${t.titleProp}",
                                        t.amountProp?.let { "сума → $it" },
                                        t.dateProp?.let { "дата → $it" },
                                        t.accountProp?.let { "рахунок → $it" },
                                        t.toAccountProp?.let { "на рахунок → $it" },
                                        t.categoryProp?.let { "категорія → $it" },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancelDiscovery, modifier = Modifier.weight(1f)) { Text("Скасувати") }
                    Button(
                        onClick = onSave,
                        enabled = discovery.roles[NotionRole.EXPENSES] != null && busy is Busy.Idle,
                        modifier = Modifier.weight(1f),
                    ) { Text("Зберегти") }
                }
            }

            config == null || !hasToken -> {
                Hint("MonoSync додаватиме кожну операцію рядком у твої бази Notion: витрати, доходи й перекази, з рахунком і категорією.")
                SectionLabel("Як підключити")
                Group {
                    SettingRow(
                        Icons.AutoMirrored.Rounded.OpenInNew, "1. Створи інтеграцію",
                        "notion.so/profile/integrations → New integration → Internal. Скопіюй Internal Integration Secret",
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.notion.so/profile/integrations"))) } },
                    )
                    GroupDivider()
                    SettingRow(
                        null, "2. Дай доступ до сторінки",
                        "Відкрий «Планувальник фінансів» → ••• → Connections → додай свою інтеграцію. Вкладені бази підхопляться самі",
                    )
                }
                Spacer(Modifier.height(12.dp))
                SecretField(token, { token = it }, "3. Встав секрет інтеграції")
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onDiscover(token) },
                    enabled = token.length > 20 && busy is Busy.Idle,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Знайти бази") }
            }

            else -> {
                Group {
                    SettingRow(
                        NotionLogo, "Синхронізація",
                        when {
                            settings.notionLastError != null -> "Помилка: ${settings.notionLastError}"
                            pendingCount > 0 -> "У черзі: $pendingCount"
                            settings.notionLastSync > 0 -> "Усе відправлено · ${dateTimeOf(settings.notionLastSync)}"
                            else -> "Готово до роботи"
                        },
                        trailing = { Switch(checked = settings.notionEnabled, onCheckedChange = onEnabled) },
                        onClick = { onEnabled(!settings.notionEnabled) },
                    )
                    GroupDivider()
                    SettingRow(
                        Icons.Rounded.Sync, "Надіслати зараз", "Повторити помилки й відправити чергу",
                        trailing = { if (running) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp) },
                        onClick = onPush,
                    )
                    GroupDivider()
                    SettingRow(
                        Icons.Rounded.Refresh, "Оновити рахунки й категорії",
                        "Рахунків: ${settings.notionAccounts.size}. Підтягни, якщо додав нові в Notion",
                        onClick = onRefreshLookups,
                    )
                }
                SectionLabel("Налаштування записів")
                Group {
                    SettingRow(
                        Icons.Rounded.Schedule, "Записувати час", "Інакше в колонці дати буде лише день",
                        trailing = { Switch(checked = settings.notionIncludeTime, onCheckedChange = onIncludeTime) },
                        onClick = { onIncludeTime(!settings.notionIncludeTime) },
                    )
                    GroupDivider()
                    SettingRow(
                        Icons.Rounded.CalendarMonth, "Відправляти операції починаючи з",
                        if (settings.notionStartAt > 0) dateTimeOf(settings.notionStartAt) else "Моменту підключення",
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                        onClick = { pickStart = true },
                    )
                    GroupDivider()
                    SettingRow(Icons.Rounded.CurrencyExchange, "Валюта сум", "${config.currency}. Інші валюти конвертуються за курсом Monobank")
                }
                SectionLabel("Бази")
                Group {
                    listOf(
                        "Витрати" to config.expenses?.name,
                        "Доходи" to config.incomes?.name,
                        "Перекази" to config.transfers?.name,
                        "Рахунки" to config.accountsName,
                        "Категорії" to config.categoriesName,
                    ).forEachIndexed { i, (k, v) ->
                        if (i > 0) GroupDivider()
                        SettingRow(Icons.Rounded.TableChart, k, v ?: "Не вибрано")
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) { Text("Відключити") }
                    Button(onClick = { onDiscover("") }, modifier = Modifier.weight(1f), enabled = busy is Busy.Idle) { Text("Змінити бази") }
                }
            }
        }
    }

    pickRole?.let { role ->
        SelectDialog(
            title = role.title,
            options = discovery?.sources.orEmpty(),
            selected = discovery?.roles?.get(role),
            label = { it.title },
            onSelect = { onRole(role, it) },
            onDismiss = { pickRole = null },
            noneLabel = "Не використовувати",
            onNone = { onRole(role, null) },
        )
    }
    if (pickCurrency) {
        SelectDialog(
            title = "Валюта сум у Notion",
            options = listOf("EUR", "UAH", "USD", "PLN", "GBP"),
            selected = discovery?.currency,
            label = { it },
            onSelect = { onCurrency(it) },
            onDismiss = { pickCurrency = false },
            noneLabel = "Визначити автоматично",
            onNone = { onCurrency(null) },
        )
    }
    if (pickStart) {
        val zone = ZoneId.systemDefault()
        val options = listOf(
            "Від цієї хвилини" to System.currentTimeMillis(),
            "Від початку сьогодні" to LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli(),
            "Від початку тижня" to LocalDate.now().minusDays(LocalDate.now().dayOfWeek.value - 1L).atStartOfDay(zone).toInstant().toEpochMilli(),
            "Від початку місяця" to LocalDate.now().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
        SelectDialog(
            title = "Відправляти в Notion операції",
            options = options,
            selected = null,
            label = { it.first },
            onSelect = { onStartAt(it.second) },
            onDismiss = { pickStart = false },
        )
    }
}

