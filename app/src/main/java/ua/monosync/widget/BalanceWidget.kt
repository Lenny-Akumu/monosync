package ua.monosync.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.flow.first
import ua.monosync.app
import ua.monosync.core.Money
import ua.monosync.data.db.Kind
import ua.monosync.data.db.cur
import ua.monosync.ui.MainActivity
import java.time.LocalDate
import java.time.ZoneId

class BalanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.app.repo
        val s = repo.settings.current()
        val accounts = repo.db.accounts().all().filter { it.confirmed && !it.hidden }
        val primary = accounts.firstOrNull { it.id == s.primaryAccountId } ?: accounts.firstOrNull()
        val zone = ZoneId.systemDefault()
        val monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val spent = primary?.let { p ->
            repo.range(monthStart, Long.MAX_VALUE).first()
                .filter { it.accountId == p.id && it.kind == Kind.EXPENSE }
                .sumOf { -it.amount }
        } ?: 0L
        val name = primary?.name ?: "MonoSync"
        val balance = primary?.balance?.let { Money.format(it, primary.cur) } ?: "—"
        val spentText = primary?.let { "Витрачено цього місяця ${Money.formatShort(spent, it.cur)}" } ?: "Додай картку в застосунку"

        provideContent {
            GlanceTheme {
                Content(name, balance, spentText)
            }
        }
    }

    @Composable
    private fun Content(name: String, balance: String, spent: String) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.primaryContainer)
                .cornerRadius(24.dp)
                .padding(16.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.height(4.dp))
            Text(balance, style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 26.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.height(6.dp))
            Text(spent, style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp))
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            runCatching { BalanceWidget().updateAll(context) }
        }
    }
}

class BalanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BalanceWidget()
}
