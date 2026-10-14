package ua.monosync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ua.monosync.core.Currency
import ua.monosync.core.Money
import ua.monosync.core.Period
import ua.monosync.data.AppSettings
import ua.monosync.data.db.SubscriptionEntity
import ua.monosync.ui.Busy
import ua.monosync.ui.components.Group
import ua.monosync.ui.components.GroupDivider
import ua.monosync.ui.components.SectionLabel
import ua.monosync.ui.components.SelectDialog
import ua.monosync.ui.components.SettingRow
import ua.monosync.ui.components.Uk
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

fun dueText(sub: SubscriptionEntity): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(sub.nextCharge).atZone(zone).toLocalDate()
    val left = ChronoUnit.DAYS.between(LocalDate.now(zone), day)
    val date = DateTimeFormatter.ofPattern("d MMMM", Uk).format(day)
    return when {
        left < 0 -> "мало списатись $date"
        left == 0L -> "сьогодні"
        left == 1L -> "завтра, $date"
        else -> "через $left дн., $date"
    }
}

/** Rough monthly cost, so weekly and yearly plans can be summed with monthly ones. */
fun monthlyCost(sub: SubscriptionEntity): Long = when (Period.byName(sub.period)) {
    Period.WEEKLY -> sub.amount * 52 / 12
    Period.MONTHLY -> sub.amount
    Period.YEARLY -> sub.amount / 12
}

@Composable
fun SubscriptionsScreen(
    settings: AppSettings,
    subscriptions: List<SubscriptionEntity>,
    busy: Busy,
    onBack: () -> Unit,
    onRemindersOn: (Boolean) -> Unit,
    onRemindDays: (Int) -> Unit,
    onRefresh: () -> Unit,
    onUpdate: (SubscriptionEntity) -> Unit,
    onDismiss: (SubscriptionEntity) -> Unit,
) {
    var pickDays by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SubscriptionEntity?>(null) }
    val scheme = MaterialTheme.colorScheme

    SubScreen("Підписки", onBack, busy) {
        val totals = subscriptions.groupBy { it.currency }
            .map { (c, l) -> Money.formatShort(l.sumOf { monthlyCost(it) }, Currency.byIso(c)) }
        Surface(shape = RoundedCornerShape(28.dp), color = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("На підписки щомісяця", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (totals.isEmpty()) "0 ₴" else totals.joinToString(" + "),
                    style = MaterialTheme.typography.headlineMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
                )
                Text(
                    "Знайдено: ${subscriptions.size}. Підписки визначаються за регулярними списаннями й відомими сервісами.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionLabel("Нагадування")
        Group {
            SettingRow(
                Icons.Rounded.NotificationsActive, "Нагадувати про списання",
                "Сповіщення перед кожним платежем",
                trailing = { Switch(checked = settings.subRemindersOn, onCheckedChange = onRemindersOn) },
                onClick = { onRemindersOn(!settings.subRemindersOn) },
            )
            GroupDivider()
            SettingRow(
                null, "За скільки днів",
                when (settings.subRemindDays) {
                    0 -> "У день списання"
                    1 -> "За 1 день"
                    else -> "За ${settings.subRemindDays} дн."
                },
                trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                onClick = { pickDays = true },
            )
        }

        SectionLabel("Активні")
        if (subscriptions.isEmpty()) {
            Text(
                "Поки нічого не знайдено. Підписки з'являться, коли накопичаться регулярні списання. " +
                    "Будь-яку операцію можна позначити як підписку вручну.",
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            Group {
                subscriptions.forEachIndexed { i, sub ->
                    if (i > 0) GroupDivider()
                    SettingRow(
                        Icons.Rounded.Subscriptions,
                        sub.name,
                        "${Period.byName(sub.period).title} · ${dueText(sub)}" + if (!sub.remind) " · без нагадування" else "",
                        trailing = {
                            Text(
                                Money.format(sub.amount, Currency.byIso(sub.currency)),
                                style = MaterialTheme.typography.titleSmall.merge(TextStyle(fontFeatureSettings = "tnum")),
                            )
                        },
                        onClick = { editing = sub },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Group {
            SettingRow(Icons.Rounded.Refresh, "Шукати підписки зараз", "Переглянути операції за останній рік", onClick = onRefresh)
        }
    }

    if (pickDays) {
        SelectDialog(
            title = "Нагадувати за",
            options = listOf(0, 1, 2, 3, 5, 7),
            selected = settings.subRemindDays,
            label = { if (it == 0) "У день списання" else "$it дн. до списання" },
            onSelect = onRemindDays,
            onDismiss = { pickDays = false },
        )
    }
    editing?.let { sub ->
        SubscriptionDialog(sub, onSave = onUpdate, onNotSubscription = { onDismiss(sub) }, onClose = { editing = null })
    }
}

@Composable
private fun SubscriptionDialog(
    sub: SubscriptionEntity,
    onSave: (SubscriptionEntity) -> Unit,
    onNotSubscription: () -> Unit,
    onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(sub.name) }
    var amount by remember { mutableStateOf(Money.toDecimal(sub.amount).toPlainString()) }
    var period by remember { mutableStateOf(Period.byName(sub.period)) }
    var remind by remember { mutableStateOf(sub.remind) }
    var pickPeriod by remember { mutableStateOf(false) }
    val currency = Currency.byIso(sub.currency)

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Підписка") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Назва") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { v -> amount = v.filter { it.isDigit() || it == '.' || it == ',' }.take(10) },
                    label = { Text("Сума") },
                    suffix = { Text(currency?.symbol ?: "") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingRow(
                    Icons.Rounded.Autorenew, "Як часто", period.title,
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    onClick = { pickPeriod = true },
                )
                SettingRow(
                    Icons.Rounded.NotificationsActive, "Нагадувати", "Наступне списання ${dueText(sub)}",
                    trailing = { Switch(checked = remind, onCheckedChange = { remind = it }) },
                    onClick = { remind = !remind },
                )
                TextButton(onClick = { onNotSubscription(); onClose() }) {
                    Text("Це не підписка", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (Money.parseMinor(amount) ?: 0) > 0,
                onClick = {
                    val zone = ZoneId.systemDefault()
                    val next = if (period.name != sub.period) {
                        var n = period.next(sub.lastCharge, zone)
                        while (n < System.currentTimeMillis() - 5 * 24 * 3600_000L) n = period.next(n, zone)
                        n
                    } else sub.nextCharge
                    onSave(sub.copy(name = name.trim(), amount = Money.parseMinor(amount) ?: sub.amount, period = period.name, nextCharge = next, remind = remind))
                    onClose()
                },
            ) { Text("Зберегти") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Скасувати") } },
    )
    if (pickPeriod) {
        SelectDialog("Як часто", Period.entries.toList(), period, { it.title }, { period = it }, { pickPeriod = false })
    }
}
