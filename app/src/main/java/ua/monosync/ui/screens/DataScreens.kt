package ua.monosync.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ua.monosync.core.Currency
import ua.monosync.core.Money
import ua.monosync.core.NotificationParser
import ua.monosync.data.AppSettings
import ua.monosync.data.db.AccountEntity
import ua.monosync.data.db.CategoryEntity
import ua.monosync.data.db.RawNotificationEntity
import ua.monosync.data.db.RuleEntity
import ua.monosync.data.db.cur
import ua.monosync.data.db.isJar
import ua.monosync.ui.components.Group
import ua.monosync.ui.components.GroupDivider
import ua.monosync.ui.components.SectionLabel
import ua.monosync.ui.components.SettingRow
import ua.monosync.ui.components.dateTimeOf
import ua.monosync.ui.components.iconForGroup

@Composable
fun AccountsScreen(
    settings: AppSettings,
    accounts: List<AccountEntity>,
    onBack: () -> Unit,
    onOpen: (AccountEntity) -> Unit,
) {
    SubScreen("Картки й рахунки", onBack) {
        Hint("Картки й банки знаходяться самі: зі сповіщень або з Monobank API. Нову картку треба підтвердити один раз, і для неї можна вибрати рахунок у Notion.")
        val groups = listOf(
            "Активні" to accounts.filter { it.confirmed && !it.hidden },
            "Очікують підтвердження" to accounts.filter { !it.confirmed && !it.hidden },
            "Приховані" to accounts.filter { it.hidden },
        )
        groups.forEach { (title, list) ->
            if (list.isEmpty()) return@forEach
            SectionLabel(title)
            Group {
                list.forEachIndexed { i, acc ->
                    if (i > 0) GroupDivider()
                    val notionName = settings.notionAccounts.firstOrNull { it.id == acc.notionAccountId }?.name
                    SettingRow(
                        when {
                            acc.id == settings.primaryAccountId -> Icons.Rounded.Star
                            acc.hidden -> Icons.Rounded.VisibilityOff
                            !acc.confirmed -> Icons.Rounded.HelpOutline
                            acc.isJar -> Icons.Rounded.Savings
                            else -> Icons.Rounded.CreditCard
                        },
                        acc.name,
                        listOfNotNull(
                            acc.cur.code,
                            acc.balance?.let { Money.format(it, acc.cur) },
                            acc.maskedPan?.let { "•• " + it.takeLast(4) },
                            if (acc.fromApi) "API" else "сповіщення",
                            notionName?.let { "Notion: $it" },
                        ).joinToString(" · "),
                        onClick = { onOpen(acc) },
                    )
                }
            }
        }
        if (accounts.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Поки порожньо. Зроби будь-яку операцію в Mono або підключи токен API.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CategoriesScreen(
    categories: List<CategoryEntity>,
    rules: List<RuleEntity>,
    notionConnected: Boolean,
    currency: Currency,
    onBack: () -> Unit,
    onDeleteRule: (String) -> Unit,
    onCreate: (name: String, budget: Long?, groupKey: String?) -> Unit,
    onUpdate: (CategoryEntity) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
) {
    val catById = remember(categories) { categories.associateBy { it.id } }
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    SubScreen("Категорії та правила", onBack) {
        Hint(
            if (notionConnected) "Категорії беруться з твоєї бази в Notion разом з бюджетами. Нові категорії, створені тут, теж з'являться в Notion."
            else "Створюй свої категорії прямо тут. Коли підключиш Notion, вони переїдуть у твою базу категорій."
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Нова категорія")
        }
        SectionLabel("Категорії")
        Group {
            categories.forEachIndexed { i, c ->
                if (i > 0) GroupDivider()
                SettingRow(
                    iconForGroup(c.groupKey), c.name,
                    listOfNotNull(
                        when {
                            c.fromNotion -> "Notion"
                            c.id.startsWith("user:") -> "твоя"
                            else -> "вбудована"
                        },
                        c.budget?.let { "бюджет ${Money.formatShort(it, currency)}" },
                    ).joinToString(" · "),
                    trailing = { Icon(Icons.Rounded.Edit, "Змінити", tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { editing = c },
                )
            }
        }
        if (rules.isNotEmpty()) {
            SectionLabel("Правила, яких навчився застосунок")
            Group {
                rules.forEachIndexed { i, r ->
                    if (i > 0) GroupDivider()
                    SettingRow(
                        null, "«${r.pattern}»", "→ ${catById[r.categoryId]?.name ?: "видалена категорія"}",
                        trailing = { IconButton(onClick = { onDeleteRule(r.pattern) }) { Icon(Icons.Rounded.Delete, "Видалити правило") } },
                    )
                }
            }
        }
    }
    if (creating) {
        CategoryDialog(
            initial = null,
            currency = currency,
            toNotion = notionConnected,
            onSave = onCreate,
            onDelete = null,
            onDismiss = { creating = false },
        )
    }
    editing?.let { c ->
        CategoryDialog(
            initial = c,
            currency = currency,
            toNotion = notionConnected,
            onSave = { name, budget, group -> onUpdate(c.copy(name = name, budget = budget, groupKey = group)) },
            onDelete = if (c.fromNotion) null else ({ onDelete(c) }),
            onDismiss = { editing = null },
        )
    }
}

@Composable
fun LogScreen(items: List<RawNotificationEntity>, onBack: () -> Unit, onClear: () -> Unit) {
    val context = LocalContext.current
    SubScreen("Журнал сповіщень", onBack) {
        Hint("Тут усе, що надіслав Monobank. Якщо якась операція не розпізналась, скопіюй журнал і надішли розробнику.")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { copy(context, items.joinToString("\n\n") { "${dateTimeOf(it.postedAt)}\n${it.title}\n${it.text}" }) },
                label = { Text("Скопіювати все") },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
            )
            AssistChip(onClick = onClear, label = { Text("Очистити") }, leadingIcon = { Icon(Icons.Rounded.Close, null) })
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Text("Поки жодного сповіщення від Mono", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        items.forEach { raw ->
            val parsed = remember(raw.id) { NotificationParser.parse(raw.title, raw.text) }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(dateTimeOf(raw.postedAt), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (parsed != null) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        listOfNotNull(raw.title, raw.text).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    Text(
                        parsed?.let { p ->
                            buildString {
                                append(if (p.direction == ua.monosync.core.Direction.IN) "+" else "−")
                                append(Money.format(p.amountMinor, p.currency))
                                p.cardPhrase?.let { append(" · картка «").append(it).append("»") }
                                p.balanceMinor?.let { append(" · баланс ").append(Money.format(it, null)) }
                                p.rate?.let { append(" · курс ").append(it) }
                                p.description?.let { append(" · ").append(it) }
                            }
                        } ?: "Не операція, пропущено",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (parsed != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (items.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("Очистити журнал") }
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("MonoSync log", text))
}
