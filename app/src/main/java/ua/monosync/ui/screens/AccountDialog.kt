package ua.monosync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ua.monosync.core.Currency
import ua.monosync.data.NotionAccount
import ua.monosync.data.db.AccountEntity
import ua.monosync.data.db.cur
import ua.monosync.data.db.isJar
import ua.monosync.ui.components.SelectDialog
import ua.monosync.ui.components.SettingRow

@Composable
fun AccountDialog(
    account: AccountEntity,
    allAccounts: List<AccountEntity>,
    notionAccounts: List<NotionAccount>,
    suggestedNotion: NotionAccount?,
    isPrimary: Boolean,
    onConfirm: (name: String, currency: Currency, notionAccountId: String?) -> Unit,
    onUpdate: (AccountEntity) -> Unit,
    onSetPrimary: () -> Unit,
    onMerge: (into: AccountEntity) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(account.name) }
    var currency by remember { mutableStateOf(account.cur) }
    var notionId by remember { mutableStateOf(account.notionAccountId ?: suggestedNotion?.id) }
    var hidden by remember { mutableStateOf(account.hidden) }
    var pickCurrency by remember { mutableStateOf(false) }
    var pickNotion by remember { mutableStateOf(false) }
    var pickMerge by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val confirming = !account.confirmed

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (account.isJar) (if (confirming) "Нова банка" else "Банка") else if (confirming) "Нова картка" else "Картка") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (confirming) {
                    Text(
                        "MonoSync побачив ${if (account.isJar) "цю банку" else "цю картку"} ${if (account.fromApi) "у Monobank API" else "у сповіщенні"}. Перевір назву й валюту.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Назва") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingRow(
                    Icons.Rounded.CurrencyExchange, "Валюта", "${currency.code} ${currency.symbol}",
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    onClick = { if (!account.fromApi) pickCurrency = true },
                )
                if (notionAccounts.isNotEmpty()) {
                    SettingRow(
                        Icons.Rounded.TableChart, "Рахунок у Notion",
                        notionAccounts.firstOrNull { it.id == notionId }?.name ?: "Не прив'язано",
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                        onClick = { pickNotion = true },
                    )
                }
                if (!confirming) {
                    SettingRow(
                        Icons.Rounded.VisibilityOff, "Приховати", "Операції не рахуються й не йдуть у Notion",
                        trailing = { Switch(checked = hidden, onCheckedChange = { hidden = it }) },
                        onClick = { hidden = !hidden },
                    )
                    if (!isPrimary) SettingRow(Icons.Rounded.Star, "Зробити основною", "Перша на головному екрані й у віджеті", onClick = { onSetPrimary(); onDismiss() })
                }
                val mergeTargets = allAccounts.filter { it.id != account.id && it.currency == account.currency }
                if (mergeTargets.isNotEmpty()) {
                    SettingRow(Icons.Rounded.CallMerge, "Це та сама картка, що…", "Об'єднати з іншою, якщо з'явився дублікат", onClick = { pickMerge = true })
                }
                SettingRow(Icons.Rounded.Delete, "Видалити картку", "Операції залишаться", onClick = { confirmDelete = true })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (confirming) onConfirm(name, currency, notionId)
                else onUpdate(account.copy(name = name.trim().ifBlank { account.name }, currency = currency.iso, notionAccountId = notionId, hidden = hidden))
                onDismiss()
            }) { Text(if (confirming) "Підтвердити" else "Зберегти") }
        },
        dismissButton = {
            TextButton(onClick = {
                if (confirming) onUpdate(account.copy(hidden = true))
                onDismiss()
            }) { Text(if (confirming) "Ігнорувати" else "Скасувати") }
        },
    )

    if (pickCurrency) {
        SelectDialog("Валюта", Currency.entries.toList(), currency, { "${it.code} ${it.symbol}" }, { currency = it }, { pickCurrency = false })
    }
    if (pickNotion) {
        SelectDialog(
            "Рахунок у Notion", notionAccounts, notionAccounts.firstOrNull { it.id == notionId }, { it.name },
            { notionId = it.id }, { pickNotion = false }, noneLabel = "Не прив'язувати", onNone = { notionId = null },
        )
    }
    if (pickMerge) {
        SelectDialog(
            "Об'єднати з", allAccounts.filter { it.id != account.id && it.currency == account.currency }, null, { it.name },
            { onMerge(it); onDismiss() }, { pickMerge = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Видалити «${account.name}»?") },
            text = { Text("Якщо картка ще активна, вона знайдеться знову з наступним сповіщенням.") },
            confirmButton = { TextButton(onClick = { onDelete(); confirmDelete = false; onDismiss() }) { Text("Видалити") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Скасувати") } },
        )
    }
}
