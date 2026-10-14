package ua.monosync.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.monosync.core.CardKeys
import ua.monosync.core.Currency
import ua.monosync.core.Money
import ua.monosync.data.AppSettings
import ua.monosync.data.db.AccountEntity
import ua.monosync.data.db.CategoryEntity
import ua.monosync.data.db.Kind
import ua.monosync.data.db.TransactionEntity
import ua.monosync.data.db.available
import ua.monosync.data.db.cur
import ua.monosync.ui.components.SectionLabel
import ua.monosync.ui.components.TxRow
import ua.monosync.ui.components.dayOf
import ua.monosync.ui.components.dayTitle
import ua.monosync.ui.theme.LocalMoneyColors
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settings: AppSettings,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    transactions: List<TransactionEntity>,
    pendingCount: Int,
    listenerEnabled: Boolean,
    monoConnected: Boolean,
    monoRunning: Boolean,
    contentPadding: PaddingValues,
    onSync: () -> Unit,
    onGrantListener: () -> Unit,
    onConfirmAccount: (AccountEntity) -> Unit,
    onOpenAccount: (AccountEntity) -> Unit,
    onOpenTx: (TransactionEntity) -> Unit,
    onOpenNotion: () -> Unit,
    onOpenMono: () -> Unit,
    onSeeAll: () -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val accById = remember(accounts) { accounts.associateBy { it.id } }
    val catById = remember(categories) { categories.associateBy { it.id } }
    val visible = accounts.filter { it.confirmed && !it.hidden }
        .sortedByDescending { it.id == settings.primaryAccountId }
    val unconfirmed = accounts.filter { !it.confirmed && !it.hidden }

    val zone = ZoneId.systemDefault()
    val monthStart = remember { LocalDate.now().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli() }
    val month = transactions.filter { it.time >= monthStart && accById[it.accountId]?.confirmed == true }
    val mainCurrency = visible.firstOrNull()?.cur ?: Currency.UAH
    val spent = month.filter { it.kind == Kind.EXPENSE && it.cur == mainCurrency }.sumOf { -it.amount }
    val earned = month.filter { it.kind == Kind.INCOME && it.cur == mainCurrency }.sumOf { it.amount }

    Column(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        TopAppBar(
            title = {
                Column {
                    Text("MonoSync", style = MaterialTheme.typography.titleLarge)
                    settings.monoClientName?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            actions = {
                if (monoConnected) {
                    val rotation = if (monoRunning) {
                        val t = rememberInfiniteTransition(label = "sync")
                        t.animateFloat(0f, -360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "r").value
                    } else 0f
                    IconButton(onClick = onSync) { Icon(Icons.Rounded.Sync, "Синхронізувати", Modifier.rotate(rotation)) }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = contentPadding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!listenerEnabled) {
                item {
                    ActionCard(
                        icon = Icons.Rounded.NotificationsActive,
                        title = "Дай доступ до сповіщень",
                        text = "Без цього MonoSync не бачить пушів Monobank. Android покаже попередження, це нормально: читаємо лише сповіщення Mono.",
                        action = "Відкрити налаштування",
                        onAction = onGrantListener,
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            items(unconfirmed, key = { "new_" + it.id }) { acc ->
                ActionCard(
                    icon = Icons.Rounded.NewReleases,
                    title = "Нова картка: ${acc.name}",
                    text = "${acc.cur.code}${acc.maskedPan?.let { " · ${it.takeLast(4)}" } ?: ""}. Підтверди її, і операції почнуть потрапляти в облік та Notion.",
                    action = "Підтвердити",
                    onAction = { onConfirmAccount(acc) },
                    tertiary = true,
                )
                Spacer(Modifier.height(12.dp))
            }

            if (visible.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 8.dp)) {
                        items(visible, key = { it.id }) { acc ->
                            BalanceCard(acc, primary = acc == visible.first(), onClick = { onOpenAccount(acc) })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            } else if (unconfirmed.isEmpty()) {
                item {
                    EmptyHero(monoConnected, onOpenMono)
                    Spacer(Modifier.height(12.dp))
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryTile(
                        Modifier.weight(1f), "Витрати за місяць", Money.formatShort(spent, mainCurrency),
                        Icons.Rounded.ArrowUpward, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    SummaryTile(
                        Modifier.weight(1f), "Надходження", Money.formatShort(earned, mainCurrency),
                        Icons.Rounded.ArrowDownward, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Spacer(Modifier.height(8.dp))
                NotionChip(settings, pendingCount, onOpenNotion)
            }

            val recent = transactions.filter { !(accById[it.accountId]?.hidden ?: false) }.take(40)
            if (recent.isEmpty()) {
                item {
                    Text(
                        "Тут з'являться операції, щойно Monobank надішле сповіщення",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            recent.groupBy { dayOf(it.time) }.forEach { (day, list) ->
                item(key = "h_$day") { SectionLabel(dayTitle(day)) }
                items(list, key = { it.id }) { tx ->
                    val first = tx == list.first()
                    val last = tx == list.last()
                    Box(Modifier.clip(groupShape(first, last))) {
                        TxRow(tx, accById[tx.accountId], catById[tx.categoryId], accById[tx.transferAccountId], settings.notionEnabled) { onOpenTx(tx) }
                    }
                }
            }
            if (recent.size >= 40) {
                item {
                    TextButton(onClick = onSeeAll, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Усі операції") }
                }
            }
        }
    }
}

fun groupShape(first: Boolean, last: Boolean) = RoundedCornerShape(
    topStart = if (first) 24.dp else 6.dp, topEnd = if (first) 24.dp else 6.dp,
    bottomStart = if (last) 24.dp else 6.dp, bottomEnd = if (last) 24.dp else 6.dp,
)

@Composable
private fun BalanceCard(acc: AccountEntity, primary: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container = if (primary) scheme.primaryContainer else scheme.surfaceContainerHigh
    val content = if (primary) scheme.onPrimaryContainer else scheme.onSurface
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.width(if (primary) 300.dp else 240.dp),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (primary) Icons.Rounded.Star else Icons.Rounded.CreditCard, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(acc.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1)
                acc.maskedPan?.let { Text("•• " + it.takeLast(4), style = MaterialTheme.typography.labelMedium) }
            }
            Text(
                acc.balance?.let { Money.format(it, acc.cur) } ?: "—",
                style = TextStyle(fontSize = if (primary) 34.sp else 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, fontFeatureSettings = "tnum"),
                maxLines = 1,
            )
            val sub = when {
                acc.creditLimit > 0 -> "Доступно ${Money.formatShort(acc.available ?: 0, acc.cur)} з кредитним лімітом"
                acc.type != null -> CardKeys.typeTitle(acc.type)?.let { "$it · ${acc.cur.code}" } ?: acc.cur.code
                else -> acc.cur.code
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.8f), maxLines = 1)
        }
    }
}

@Composable
private fun SummaryTile(modifier: Modifier, label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, container: Color, content: Color) {
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = container, contentColor = content) {
                Icon(icon, null, Modifier.padding(6.dp).size(18.dp))
            }
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge.merge(TextStyle(fontFeatureSettings = "tnum")))
        }
    }
}

@Composable
private fun NotionChip(settings: AppSettings, pending: Int, onClick: () -> Unit) {
    val (icon, text) = when {
        settings.notionConfig == null -> Icons.Rounded.CloudUpload to "Підключи Notion, щоб вести облік там"
        !settings.notionEnabled -> Icons.Rounded.CloudUpload to "Синхронізацію з Notion призупинено"
        settings.notionLastError != null -> Icons.Rounded.ErrorOutline to "Notion: ${settings.notionLastError}"
        pending > 0 -> Icons.Rounded.CloudUpload to "Notion: у черзі $pending"
        else -> Icons.Rounded.CloudDone to "Notion синхронізовано"
    }
    AssistChip(
        onClick = onClick,
        label = { Text(text, maxLines = 1) },
        leadingIcon = { Icon(icon, null, Modifier.size(AssistChipDefaults.IconSize)) },
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    text: String,
    action: String,
    onAction: () -> Unit,
    tertiary: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (tertiary) scheme.tertiaryContainer else scheme.secondaryContainer,
            contentColor = if (tertiary) scheme.onTertiaryContainer else scheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(action) }
        }
    }
}

@Composable
private fun EmptyHero(monoConnected: Boolean, onOpenMono: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(32.dp), color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Привіт!", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (monoConnected) "Підтягую картки з Monobank…"
                else "Картки з'являться після першого сповіщення від Mono. Або підключи токен Monobank, щоб одразу бачити всі баланси.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!monoConnected) {
                FilledTonalButton(onClick = onOpenMono, modifier = Modifier.align(Alignment.End)) { Text("Підключити Monobank") }
            }
        }
    }
}

@Composable
fun incomeColor() = LocalMoneyColors.current.income

@Suppress("unused")
private fun Modifier.clickableIf(enabled: Boolean, onClick: () -> Unit) = if (enabled) clickable(onClick = onClick) else this
