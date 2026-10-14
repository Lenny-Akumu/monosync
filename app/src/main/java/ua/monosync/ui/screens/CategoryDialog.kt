package ua.monosync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ua.monosync.core.Currency
import ua.monosync.core.Mcc
import ua.monosync.core.Money
import ua.monosync.data.db.CategoryEntity
import ua.monosync.ui.components.iconForGroup

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryDialog(
    initial: CategoryEntity?,
    currency: Currency,
    toNotion: Boolean,
    onSave: (name: String, budget: Long?, groupKey: String?) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var budget by remember { mutableStateOf(initial?.budget?.let { Money.toDecimal(it).toPlainString() } ?: "") }
    var group by remember { mutableStateOf(initial?.groupKey) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Нова категорія" else "Категорія") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Назва") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = budget,
                    onValueChange = { v -> budget = v.filter { it.isDigit() || it == '.' || it == ',' }.take(10) },
                    label = { Text("Місячний бюджет") },
                    placeholder = { Text("Без ліміту") },
                    suffix = { Text(currency.symbol) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Іконка", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mcc.groups.forEach { g ->
                        val selected = g.key == group
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerHighest)
                                .clickable { group = if (selected) null else g.key },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                iconForGroup(g.key), g.title,
                                tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
                Text(
                    when {
                        initial?.fromNotion == true -> "Зміни назви й бюджету збережуться і в Notion."
                        toNotion -> "Категорія з'явиться і у твоїй базі категорій у Notion."
                        else -> "Категорія збережеться на телефоні. Коли підключиш Notion, вона переїде туди."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Видалити категорію", color = scheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(name.trim(), budget.takeIf { it.isNotBlank() }?.let { Money.parseMinor(it) }, group)
                    onDismiss()
                },
            ) { Text(if (initial == null) "Створити" else "Зберегти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Видалити «${initial?.name}»?") },
            text = { Text("Операції з цією категорією стануть без категорії.") },
            confirmButton = { TextButton(onClick = { onDelete(); confirmDelete = false; onDismiss() }) { Text("Видалити") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Скасувати") } },
        )
    }
}
