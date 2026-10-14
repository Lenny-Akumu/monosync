package ua.monosync.sync

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch
import ua.monosync.app
import ua.monosync.data.Repository
import ua.monosync.data.db.Kind
import ua.monosync.data.db.SyncState
import ua.monosync.widget.BalanceWidget

class MonoListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in MONO_PACKAGES) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.text(Notification.EXTRA_TITLE_BIG) ?: extras.text(Notification.EXTRA_TITLE)
        val big = extras.text(Notification.EXTRA_BIG_TEXT)
        val text = big ?: extras.text(Notification.EXTRA_TEXT)
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
        val body = listOfNotNull(text, lines?.takeIf { text == null }).joinToString("\n").ifBlank { null }
        if (title == null && body == null) return

        val app = applicationContext.app
        app.scope.launch {
            val result = app.repo.ingestNotification(sbn.key ?: sbn.id.toString(), sbn.postTime, title, body)
            if (result is Repository.IngestResult.Recorded) {
                if (result.newAccount) Notifier.newAccount(applicationContext, result.account)
                if (app.repo.settings.current().notifyOnRecord) {
                    val cat = result.tx.categoryId?.let { id -> app.repo.db.categories().all().firstOrNull { it.id == id }?.name }
                    Notifier.recorded(applicationContext, result.tx, if (result.tx.kind == Kind.EXPENSE) cat else null)
                }
                BalanceWidget.refresh(applicationContext)
                if (result.tx.kind == Kind.EXPENSE) afterNewCharges(applicationContext)
                if (result.tx.syncState == SyncState.PENDING) SyncScheduler.notionNow(applicationContext, 5)
                if (app.repo.secrets.monoToken.value != null) {
                    SyncScheduler.monoNow(
                        applicationContext,
                        delaySeconds = 90,
                        accountId = result.account.id.takeIf { result.account.fromApi },
                    )
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching { activeNotifications }.getOrNull()
            ?.sortedBy { it.postTime }
            ?.forEach { onNotificationPosted(it) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        requestRebind(this)
    }

    private fun Bundle.text(key: String): String? = getCharSequence(key)?.toString()?.trim()?.ifBlank { null }

    companion object {
        val MONO_PACKAGES = setOf("com.ftband.mono", "com.ftband.mono.business")

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun requestRebind(context: Context) {
            runCatching { NotificationListenerService.requestRebind(ComponentName(context, MonoListenerService::class.java)) }
        }

        fun settingsIntentAction(): String = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
    }
}
