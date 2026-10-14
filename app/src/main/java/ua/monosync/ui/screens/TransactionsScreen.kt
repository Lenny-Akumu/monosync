package ua.monosync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import ua.monosync.core.Money
import ua.monosync.data.db.AccountEntity
import ua.monosync.data.db.CategoryEntity
import ua.monosync.data.db.Kind
import ua.monosync.data.db.TransactionEntity
import ua.monosync.data.db.cur
import ua.monosync.ui.components.SectionLabel
import ua.monosync.ui.components.SelectDialog
import ua.monosync.ui.components.TxRow
import ua.monosync.ui.components.dayOf
import ua.monosync.ui.components.dayTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    transactions: List<TransactionEntity>,
    showSync: Boolean,
    contentPadding: PaddingValues,
    onOpenTx: (TransactionEntity) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var pickAccount by remember { mutableStateOf(false) }
    var pickCategory by remember { mutableStateOf(false) }
    val accById = remember(accounts) { accounts.associateBy { it.id } }
    val catById = remember(categories) { categories.associateBy { it.id } }

    val filtered = remember(transactions, query, kind, accountId, categoryId) {
        val q = query.trim().lowercase()
        transactions.filter { tx ->
            (kind == null || tx.kind == kind) &&
                (accountId == null || tx.accountId == accountId || tx.transferAccountId == accountId) &&
                (categoryId == null || tx.categoryId == categoryId) &&
                (q.isEmpty() || tx.description.lowercase().contains(q) || (tx.comment?.lowercase()?.contains(q) ?: false) ||
                    Money.format(tx.amount, null).contains(q))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Операції",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 12.dp),
        )
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Пошук за назвою, сумою, коментарем") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Очистити") }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            item { FilterChip(selected = kind == null, onClick = { kind = null }, label = { Text("Усі") }) }
            item { FilterChip(selected = kind == Kind.EXPENSE, onClick = { kind = Kind.EXPENSE }, label = { Text("Витрати") }) }
            item { FilterChip(selected = kind == Kind.INCOME, onClick = { kind = Kind.INCOME }, label = { Text("Надходження") }) }
            item { FilterChip(selected = kind == Kind.TRANSFER, onClick = { kind = Kind.TRANSFER }, label = { Text("Перекази") }) }
            item {
                FilterChip(
                    selected = accountId != null,
                    onClick = { pickAccount = true },
                    label = { Text(accountId?.let { accById[it]?.name } ?: "Картка") },
                    leadingIcon = { Icon(Icons.Rounded.CreditCard, null) },
                )
            }
            item {
                FilterChip(
                    selected = categoryId != null,
                    onClick = { pickCategory = true },
                    label = { Text(categoryId?.let { catById[it]?.name } ?: "Категорія") },
                )
            }
        }

        val grouped = filtered.groupBy { dayOf(it.time) }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (filtered.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text("Нічого не знайдено", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            grouped.forEach { (day, list) ->
                item(key = "h_$day") {
                    val spent = list.filter { it.kind == Kind.EXPENSE }.groupBy { it.cur }
                        .map { (c, l) -> Money.formatShort(l.sumOf { -it.amount }, c) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(dayTitle(day), Modifier.weight(1f))
                        if (spent.isNotEmpty()) {
                            Text(
                                "−" + spent.joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp, end = 8.dp),
                            )
                        }
                    }
                }
                items(list, key = { it.id }) { tx ->
                    Box(Modifier.clip(groupShape(tx == list.first(), tx == list.last()))) {
                        TxRow(tx, accById[tx.accountId], catById[tx.categoryId], accById[tx.transferAccountId], showSync) { onOpenTx(tx) }
                    }
                }
            }
        }
    }

    if (pickAccount) {
        SelectDialog(
            title = "Картка",
            options = accounts.filter { !it.hidden },
            selected = accounts.firstOrNull { it.id == accountId },
            label = { "${it.name} · ${it.cur.code}" },
            onSelect = { accountId = it.id },
            onDismiss = { pickAccount = false },
            noneLabel = "Усі картки",
            onNone = { accountId = null },
        )
    }
    if (pickCategory) {
        SelectDialog(
            title = "Категорія",
            options = categories,
            selected = categories.firstOrNull { it.id == categoryId },
            label = { it.name },
            onSelect = { categoryId = it.id },
            onDismiss = { pickCategory = false },
            noneLabel = "Усі категорії",
            onNone = { categoryId = null },
        )
    }
}
