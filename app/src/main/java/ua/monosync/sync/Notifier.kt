package ua.monosync.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ua.monosync.R
import ua.monosync.core.Currency
import ua.monosync.core.Money
import ua.monosync.data.db.AccountEntity
import ua.monosync.data.db.SubscriptionEntity
import ua.monosync.data.db.TransactionEntity
import ua.monosync.data.db.cur
import ua.monosync.data.db.isJar
import ua.monosync.ui.MainActivity

object Notifier {
    private const val CH_ACCOUNTS = "accounts"
    private const val CH_RECORDS = "records"
    private const val CH_SUBS = "subscriptions"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ACCOUNTS, "Нові картки", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Коли MonoSync бачить картку, якої ще немає в додатку"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SUBS, "Нагадування про підписки", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "За кілька днів до списання за підпискою"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORDS, "Записані операції", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Тихе підтвердження кожного запису"
            }
        )
    }

    private fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun open(context: Context, extra: Pair<String, String>?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            extra?.let { putExtra(it.first, it.second) }
        }
        return PendingIntent.getActivity(
            context, (extra?.second ?: "main").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun newAccount(context: Context, account: AccountEntity) {
        if (!allowed(context)) return
        val n = NotificationCompat.Builder(context, CH_ACCOUNTS)
            .setSmallIcon(R.drawable.ic_stat_mono)
            .setContentTitle(if (account.isJar) "Знайдено нову банку" else "Знайдено нову картку")
            .setContentText("${account.name} (${account.cur.code}). Натисни, щоб підтвердити.")
            .setAutoCancel(true)
            .setContentIntent(open(context, "confirm_account" to account.id))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(account.id.hashCode(), n) }
    }

    fun subscriptionDue(context: Context, sub: SubscriptionEntity, daysLeft: Int) {
        if (!allowed(context)) return
        val cur = Currency.byIso(sub.currency)
        val whenText = when (daysLeft) {
            0 -> "Сьогодні"
            1 -> "Завтра"
            else -> "Через $daysLeft дн."
        }
        val date = java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale.forLanguageTag("uk-UA"))
            .format(java.time.Instant.ofEpochMilli(sub.nextCharge).atZone(java.time.ZoneId.systemDefault()))
        val n = NotificationCompat.Builder(context, CH_SUBS)
            .setSmallIcon(R.drawable.ic_stat_mono)
            .setContentTitle("Скоро списання: ${sub.name}")
            .setContentText("$whenText, $date · ${Money.format(sub.amount, cur)}")
            .setAutoCancel(true)
            .setContentIntent(open(context, "subscriptions" to "open"))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(("sub" + sub.id).hashCode(), n) }
    }

    fun recorded(context: Context, tx: TransactionEntity, categoryName: String?) {
        if (!allowed(context)) return
        val n = NotificationCompat.Builder(context, CH_RECORDS)
            .setSmallIcon(R.drawable.ic_stat_mono)
            .setContentTitle("${Money.format(tx.amount, tx.cur, signed = true)} · ${tx.description}")
            .setContentText(categoryName?.let { "Записано в «$it»" } ?: "Записано, категорію можна змінити")
            .setAutoCancel(true)
            .setContentIntent(open(context, "transaction" to tx.id.toString()))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(("tx" + tx.id).hashCode(), n) }
    }
}
