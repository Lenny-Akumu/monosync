package ua.monosync.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.monosync.core.Currency
import ua.monosync.core.Money
import ua.monosync.data.db.AccountEntity
import ua.monosync.data.db.CategoryEntity
import ua.monosync.data.db.Kind
import ua.monosync.data.db.TransactionEntity
import ua.monosync.data.db.cur
import ua.monosync.ui.components.Donut
import ua.monosync.ui.components.Dot
import ua.monosync.ui.components.ProgressLine
import ua.monosync.ui.components.Slice
import ua.monosync.ui.components.Uk
import ua.monosync.ui.theme.LocalMoneyColors
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun AnalyticsScreen(
    month: YearMonth,
    transactions: List<TransactionEntity>,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    budgetCurrency: Currency?,
    contentPadding: PaddingValues,
    onMonth: (YearMonth) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val accById = remember(accounts) { accounts.associateBy { it.id } }
    val catById = remember(categories) { categories.associateBy { it.id } }
    val txs = transactions.filter { accById[it.accountId]?.let { a -> a.confirmed && !a.hidden } ?: false }
    val currencies = txs.map { it.cur }.distinct().sortedBy { it.ordinal }
    var currency by remember(currencies) { mutableStateOf(currencies.firstOrNull() ?: Currency.UAH) }

    val inCur = txs.filter { it.cur == currency }
    val expenses = inCur.filter { it.kind == Kind.EXPENSE }
    val spent = expenses.sumOf { -it.amount }
    val earned = inCur.filter { it.kind == Kind.INCOME }.sumOf { it.amount }

    val palette = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.error, scheme.primaryContainer, scheme.tertiaryContainer, scheme.outline)
    val byCat = expenses.groupBy { it.categoryId }
        .map { (id, l) -> (id?.let { catById[it] }) to l.sumOf { -it.amount } }
        .sortedByDescending { it.second }
    val top = byCat.take(6)
    val rest = byCat.drop(6).sumOf { it.second }
    val slices = top.mapIndexed { i, (c, v) -> Slice(c?.name ?: "Без категорії", v, palette[i % palette.size]) } +
        if (rest > 0) listOf(Slice("Інше", rest, scheme.outlineVariant)) else emptyList()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                DateTimeFormatter.ofPattern("LLLL yyyy", Uk).format(month).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            FilledTonalIconButton(onClick = { onMonth(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Попередній") }
            Spacer(Modifier.width(4.dp))
            FilledTonalIconButton(onClick = { onMonth(month.plusMonths(1)) }, enabled = month < YearMonth.now()) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Наступний")
            }
        }

        if (currencies.size > 1) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                currencies.forEachIndexed { i, c ->
                    SegmentedButton(
                        selected = c == currency,
                        onClick = { currency = c },
                        shape = SegmentedButtonDefaults.itemShape(i, currencies.size),
                    ) { Text(c.code) }
                }
            }
        }

        Surface(shape = RoundedCornerShape(32.dp), color = scheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
                    Donut(slices, Modifier.fillMaxSize())
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            Money.formatShort(spent, currency),
                            style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                        )
                        Text("витрачено", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Stat("Надходження", Money.formatShort(earned, currency), LocalMoneyColors.current.income)
                    Stat("Різниця", Money.formatShort(earned - spent, currency), if (earned >= spent) LocalMoneyColors.current.income else scheme.error)
                    Stat("Операцій", "${inCur.size}", scheme.onSurface)
                }
            }
        }

        DailyBars(month, expenses)

        if (byCat.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Категорії", style = MaterialTheme.typography.titleMedium)
                    byCat.forEachIndexed { i, (cat, value) ->
                        val color = if (i < 6) palette[i % palette.size] else scheme.outlineVariant
                        val budget = cat?.budget?.takeIf { budgetCurrency == null || budgetCurrency == currency }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(color)
                                Spacer(Modifier.width(10.dp))
                                Text(cat?.name ?: "Без категорії", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    Money.formatShort(value, currency) + (budget?.let { " / " + Money.formatShort(it, currency) } ?: ""),
                                    style = MaterialTheme.typography.labelLarge.merge(TextStyle(fontFeatureSettings = "tnum")),
                                )
                            }
                            val fraction = if (budget != null && budget > 0) value.toFloat() / budget else value.toFloat() / (byCat.first().second.coerceAtLeast(1))
                            ProgressLine(fraction, if (budget != null && value > budget) scheme.error else color)
                        }
                    }
                }
            }
        } else {
            Text(
                "У цьому місяці витрат ще немає",
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium.merge(TextStyle(fontFeatureSettings = "tnum")), color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DailyBars(month: YearMonth, expenses: List<TransactionEntity>) {
    val scheme = MaterialTheme.colorScheme
    val days = month.lengthOfMonth()
    val perDay = LongArray(days)
    expenses.forEach { tx ->
        val d = ua.monosync.ui.components.dayOf(tx.time)
        if (d.year == month.year && d.monthValue == month.monthValue) perDay[d.dayOfMonth - 1] += -tx.amount
    }
    val max = (perDay.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val today = java.time.LocalDate.now()
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("По днях", style = MaterialTheme.typography.titleMedium)
            val bar = scheme.primary
            val track = scheme.surfaceContainerHighest
            val todayColor = scheme.tertiary
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                val slot = size.width / days
                val w = (slot * 0.62f).coerceAtLeast(2f)
                for (i in 0 until days) {
                    val x = i * slot + (slot - w) / 2
                    drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2, w / 2))
                    val h = size.height * perDay[i] / max
                    if (h > 0) {
                        val isToday = today.year == month.year && today.monthValue == month.monthValue && today.dayOfMonth == i + 1
                        drawRoundRect(if (isToday) todayColor else bar, Offset(x, size.height - h), Size(w, h), CornerRadius(w / 2, w / 2))
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("1", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("$days", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}
