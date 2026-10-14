package ua.monosync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.monosync.core.Currency
import ua.monosync.core.Money
import ua.monosync.data.db.AccountEntity
import ua.monosync.data.db.CategoryEntity
import ua.monosync.data.db.Kind
import ua.monosync.data.db.Source
import ua.monosync.data.db.SyncState
import ua.monosync.data.db.TransactionEntity
import ua.monosync.data.db.cur
import ua.monosync.ui.components.Group
import ua.monosync.ui.components.GroupDivider
import ua.monosync.ui.components.IconBadge
import ua.monosync.ui.components.SelectDialog
import ua.monosync.ui.components.SettingRow
import ua.monosync.ui.components.dateTimeOf
import ua.monosync.ui.components.iconFor
import ua.monosync.ui.components.iconForGroup
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionSheet(
    initial: TransactionEntity,
    isNew: Boolean,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    notionEnabled: Boolean,
    onSave: (TransactionEntity, categoryChanged: Boolean) -> Unit,
    onDelete: (TransactionEntity) -> Unit,
    onResend: (TransactionEntity) -> Unit,
    onDismiss: () -> Unit,
    budgetCurrency: Currency = Currency.UAH,
    notionCategories: Boolean = false,
    onCreateCategory: (String, Long?, String?, (String) -> Unit) -> Unit = { _, _, _, _ -> },
) {
    var newCategory by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val usable = accounts.filter { !it.hidden }
    var description by remember { mutableStateOf(initial.description) }
    var amountText by remember { mutableStateOf(if (isNew) "" else Money.toDecimal(abs(initial.amount)).toPlainString()) }
    var kind by remember { mutableStateOf(initial.kind) }
    var accountId by remember { mutableStateOf(initial.accountId) }
    var transferId by remember { mutableStateOf(initial.transferAccountId) }
    var incoming by remember { mutableStateOf(initial.amount > 0) }
    var categoryId by remember { mutableStateOf(initial.categoryId) }
    var comment by remember { mutableStateOf(initial.comment ?: "") }
    var time by remember { mutableStateOf(initial.time) }
    var pickAccount by remember { mutableStateOf(false) }
    var pickTransfer by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val account = accounts.firstOrNull { it.id == accountId }
    val currency = account?.cur ?: initial.cur
    val amountMinor = Money.parseMinor(amountText)
    val category = categories.firstOrNull { it.id == categoryId }
    val scheme = MaterialTheme.colorScheme

    fun build(): TransactionEntity? {
        val a = amountMinor ?: return null
        if (a <= 0) return null
        val signed = when (kind) {
            Kind.INCOME -> a
            Kind.TRANSFER -> if (incoming) a else -a
            else -> -a
        }
        return initial.copy(
            description = description.trim().ifBlank { if (kind == Kind.INCOME) "Поповнення" else "Витрата" },
            genericDescription = initial.genericDescription && description == initial.description,
            amount = signed,
            currency = (account?.cur ?: initial.cur).iso,
            kind = kind,
            accountId = accountId,
            transferAccountId = if (kind == Kind.TRANSFER) transferId else null,
            categoryId = if (kind == Kind.EXPENSE) categoryId else null,
            comment = comment.trim().ifBlank { null },
            time = time,
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = scheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBadge(
                iconFor(initial.copy(kind = kind), category),
                if (kind == Kind.INCOME) scheme.tertiaryContainer else scheme.secondaryContainer,
                if (kind == Kind.INCOME) scheme.onTertiaryContainer else scheme.onSecondaryContainer,
                size = 56.dp, corner = 18.dp,
            )
            OutlinedTextField(
                value = amountText,
                onValueChange = { v -> amountText = v.filter { it.isDigit() || it == '.' || it == ',' }.take(12) },
                placeholder = { Text("0,00", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                suffix = { Text(currency.symbol) },
                textStyle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, fontFeatureSettings = "tnum"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Назва") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            val kinds = listOf(Kind.EXPENSE to "Витрата", Kind.INCOME to "Надходження", Kind.TRANSFER to "Переказ")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { i, (k, label) ->
                    SegmentedButton(
                        selected = kind == k,
                        onClick = {
                            kind = k
                            if (k == Kind.INCOME) incoming = true
                            if (k == Kind.EXPENSE) incoming = false
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                    ) { Text(label, maxLines = 1) }
                }
            }

            if (kind == Kind.EXPENSE) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    categories.forEach { c ->
                        FilterChip(
                            selected = c.id == categoryId,
                            onClick = { categoryId = if (categoryId == c.id) null else c.id },
                            label = { Text(c.name) },
                            leadingIcon = {
                                Icon(if (c.id == categoryId) Icons.Rounded.Check else iconForGroup(c.groupKey), null, Modifier.size(FilterChipDefaults.IconSize))
                            },
                        )
                    }
                    FilterChip(
                        selected = false,
                        onClick = { newCategory = true },
                        label = { Text("Нова") },
                        leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(FilterChipDefaults.IconSize)) },
                    )
                }
            }

            Group {
                SettingRow(
                    Icons.Rounded.CreditCard,
                    if (kind == Kind.TRANSFER && incoming) "На картку" else if (kind == Kind.TRANSFER) "З картки" else "Картка",
                    account?.let { "${it.name} · ${it.cur.code}" } ?: "Не вибрано",
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    onClick = { pickAccount = true },
                )
                if (kind == Kind.TRANSFER) {
                    GroupDivider()
                    SettingRow(
                        Icons.Rounded.SwapHoriz,
                        if (incoming) "З рахунку" else "На рахунок",
                        accounts.firstOrNull { it.id == transferId }?.name ?: "Не вибрано",
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                        onClick = { pickTransfer = true },
                    )
                    GroupDivider()
                    SettingRow(
                        null, "Напрямок", if (incoming) "Гроші прийшли на цю картку" else "Гроші пішли з цієї картки",
                        trailing = { TextButton(onClick = { incoming = !incoming }) { Text("Змінити") } },
                    )
                }
                GroupDivider()
                SettingRow(
                    Icons.Rounded.CalendarMonth, "Дата", dateTimeOf(time),
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    onClick = { pickDate = true },
                )
            }

            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it },
                label = { Text("Коментар") },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            if (!isNew) {
                Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoLine("Джерело", when (initial.source) {
                            Source.NOTIFICATION -> if (initial.monoId != null) "Сповіщення, звірено з API" else "Сповіщення"
                            Source.API -> "Monobank API"
                            else -> "Вручну"
                        })
                        initial.mcc?.let { InfoLine("MCC", it.toString()) }
                        if (initial.operationAmount != null && initial.operationCurrency != null) {
                            InfoLine("Сума операції", Money.format(initial.operationAmount, Currency.byIso(initial.operationCurrency), signed = true))
                        }
                        initial.balanceAfter?.let { InfoLine("Баланс після", Money.format(it, initial.cur)) }
                        if (notionEnabled) {
                            InfoLine("Notion", when (initial.syncState) {
                                SyncState.SYNCED -> "Відправлено"
                                SyncState.PENDING -> "У черзі"
                                SyncState.ERROR -> "Помилка: ${initial.syncError ?: "невідома"}"
                                else -> "Не відправляється"
                            })
                        }
                    }
                }
                if (notionEnabled && initial.syncState != SyncState.SYNCED && account?.confirmed == true) {
                    TextButton(onClick = { onResend(initial); onDismiss() }) { Text("Надіслати в Notion") }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isNew) {
                    OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Видалити")
                    }
                }
                Button(
                    onClick = {
                        build()?.let { onSave(it, it.categoryId != initial.categoryId); onDismiss() }
                    },
                    enabled = (amountMinor ?: 0) > 0 && account != null && (kind != Kind.TRANSFER || transferId != null),
                    modifier = Modifier.weight(1f),
                ) { Text(if (isNew) "Додати" else "Зберегти") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (newCategory) {
        CategoryDialog(
            initial = null,
            currency = budgetCurrency,
            toNotion = notionCategories,
            onSave = { name, budget, group -> onCreateCategory(name, budget, group) { id -> categoryId = id } },
            onDelete = null,
            onDismiss = { newCategory = false },
        )
    }
    if (pickAccount) {
        SelectDialog("Картка", usable, account, { "${it.name} · ${it.cur.code}" }, { accountId = it.id }, { pickAccount = false })
    }
    if (pickTransfer) {
        SelectDialog(
            if (incoming) "З рахунку" else "На рахунок",
            usable.filter { it.id != accountId },
            accounts.firstOrNull { it.id == transferId },
            { "${it.name} · ${it.cur.code}" },
            { transferId = it.id },
            { pickTransfer = false },
        )
    }
    if (pickDate) {
        val zone = ZoneId.systemDefault()
        val local = Instant.ofEpochMilli(time).atZone(zone)
        val state = rememberDatePickerState(initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val day = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        val t: LocalTime = local.toLocalTime()
                        time = day.atTime(t).atZone(zone).toInstant().toEpochMilli()
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Скасувати") } },
        ) { DatePicker(state = state) }
    }
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Видалити операцію?") },
            text = { Text(if (initial.notionPageId != null) "Рядок у Notion теж буде видалено." else "Це не вплине на Monobank.") },
            confirmButton = { TextButton(onClick = { onDelete(initial); confirmDelete = false; onDismiss() }) { Text("Видалити") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Скасувати") } },
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, modifier = Modifier.weight(1.4f))
    }
}
