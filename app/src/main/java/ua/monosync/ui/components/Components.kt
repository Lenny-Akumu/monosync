package ua.monosync.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AttachMoney
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Checkroom
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalAtm
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalGroceryStore
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Redeem
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ua.monosync.core.Currency
import ua.monosync.core.Money
import ua.monosync.data.db.AccountEntity
import ua.monosync.data.db.CategoryEntity
import ua.monosync.data.db.Kind
import ua.monosync.data.db.SyncState
import ua.monosync.data.db.TransactionEntity
import ua.monosync.data.db.cur
import ua.monosync.ui.theme.LocalMoneyColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val Uk: Locale = Locale.forLanguageTag("uk-UA")

fun iconForGroup(key: String?): ImageVector = when (key) {
    "groceries" -> Icons.Rounded.LocalGroceryStore
    "cafe" -> Icons.Rounded.LocalCafe
    "taxi" -> Icons.Rounded.LocalTaxi
    "transport" -> Icons.Rounded.DirectionsBus
    "fuel" -> Icons.Rounded.LocalGasStation
    "health" -> Icons.Rounded.LocalHospital
    "clothes" -> Icons.Rounded.Checkroom
    "fun" -> Icons.Rounded.SportsEsports
    "digital" -> Icons.Rounded.Subscriptions
    "mobile" -> Icons.Rounded.Smartphone
    "utilities" -> Icons.Rounded.Home
    "travel" -> Icons.Rounded.Flight
    "home" -> Icons.Rounded.Home
    "electronics" -> Icons.Rounded.Devices
    "beauty" -> Icons.Rounded.Face
    "education" -> Icons.Rounded.School
    "pets" -> Icons.Rounded.Pets
    "gifts" -> Icons.Rounded.Redeem
    "transfers" -> Icons.Rounded.SwapHoriz
    "cash" -> Icons.Rounded.LocalAtm
    "bills" -> Icons.Rounded.Receipt
    "energy" -> Icons.Rounded.Bolt
    else -> Icons.Rounded.Category
}

fun iconFor(tx: TransactionEntity, category: CategoryEntity?): ImageVector = when (tx.kind) {
    Kind.INCOME -> Icons.Rounded.Payments
    Kind.TRANSFER -> Icons.Rounded.SwapHoriz
    else -> if (category != null) iconForGroup(category.groupKey) else Icons.Rounded.AttachMoney
}

@Composable
fun amountColor(tx: TransactionEntity): Color = when (tx.kind) {
    Kind.INCOME -> LocalMoneyColors.current.income
    Kind.TRANSFER -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
fun IconBadge(icon: ImageVector, container: Color, content: Color, size: Dp = 44.dp, corner: Dp = 14.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(container),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = content, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
fun SyncGlyph(state: String, modifier: Modifier = Modifier) {
    val (icon, tint) = when (state) {
        SyncState.SYNCED -> Icons.Rounded.CloudDone to MaterialTheme.colorScheme.primary
        SyncState.PENDING -> Icons.Rounded.CloudUpload to MaterialTheme.colorScheme.onSurfaceVariant
        SyncState.ERROR -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
        else -> Icons.Rounded.CloudOff to MaterialTheme.colorScheme.outlineVariant
    }
    Icon(icon, null, tint = tint, modifier = modifier.size(14.dp))
}

@Composable
fun TxRow(
    tx: TransactionEntity,
    account: AccountEntity?,
    category: CategoryEntity?,
    transferTo: AccountEntity?,
    showSync: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val subtitle = buildString {
        when (tx.kind) {
            Kind.TRANSFER -> if (tx.amount < 0) append("${account?.name ?: "?"} → ${transferTo?.name ?: "?"}") else append("${transferTo?.name ?: "?"} → ${account?.name ?: "?"}")
            Kind.INCOME -> append("Надходження")
            else -> append(category?.name ?: "Без категорії")
        }
        append(" · ")
        append(timeOf(tx.time))
        if (tx.kind != Kind.TRANSFER && account != null) append(" · ").append(account.name)
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = scheme.surfaceContainerLow),
        leadingContent = {
            IconBadge(
                iconFor(tx, category),
                if (tx.kind == Kind.INCOME) scheme.tertiaryContainer else scheme.secondaryContainer,
                if (tx.kind == Kind.INCOME) scheme.onTertiaryContainer else scheme.onSecondaryContainer,
            )
        },
        headlineContent = { Text(tx.description, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    Money.format(tx.amount, tx.cur, signed = tx.kind == Kind.INCOME),
                    style = MaterialTheme.typography.titleSmall.merge(TextStyle(fontFeatureSettings = "tnum")),
                    color = amountColor(tx),
                    fontWeight = FontWeight.SemiBold,
                )
                if (tx.operationAmount != null && tx.operationCurrency != null) {
                    Text(
                        Money.format(tx.operationAmount, Currency.byIso(tx.operationCurrency)),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                } else if (showSync) {
                    SyncGlyph(tx.syncState)
                }
            }
        },
    )
}

fun timeOf(time: Long): String =
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

fun dayOf(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()

fun dayTitle(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Сьогодні"
        today.minusDays(1) -> "Вчора"
        else -> {
            val pattern = if (day.year == today.year) "d MMMM, EEEE" else "d MMMM yyyy"
            DateTimeFormatter.ofPattern(pattern, Uk).format(day)
        }
    }
}

fun dateTimeOf(time: Long): String =
    DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Uk).format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** Rounded group of rows, like Pixel settings. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { Column { content() } }
}

@Composable
fun GroupDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.surface, thickness = 2.dp)

@Composable
fun SettingRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = icon?.let { ic -> @Composable { Icon(ic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { sub -> @Composable { Text(sub, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = trailing,
    )
}

@Composable
fun <T> SelectDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    noneLabel: String? = null,
    onNone: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (noneLabel != null && onNone != null) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onNone(); onDismiss() }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == null, onClick = { onNone(); onDismiss() })
                            Text(noneLabel)
                        }
                    }
                }
                items(options) { o ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(o); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = o == selected, onClick = { onSelect(o); onDismiss() })
                        Text(label(o))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрити") } },
    )
}

data class Slice(val label: String, val value: Long, val color: Color)

@Composable
fun Donut(slices: List<Slice>, modifier: Modifier = Modifier, thickness: Dp = 22.dp, track: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    val total = slices.sumOf { it.value }.coerceAtLeast(1)
    Canvas(modifier) {
        val stroke = thickness.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        var start = -90f
        val gap = if (slices.size > 1) 2.5f else 0f
        slices.forEach { s ->
            val sweep = 360f * s.value / total
            if (sweep > gap) {
                drawArc(s.color, start + gap / 2, sweep - gap, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            }
            start += sweep
        }
    }
}

@Composable
fun ProgressLine(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(CircleShape).background(color))
    }
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(10.dp).clip(CircleShape).background(color))

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))
