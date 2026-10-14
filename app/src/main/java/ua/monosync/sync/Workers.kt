package ua.monosync.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import ua.monosync.app
import ua.monosync.data.remote.ApiException
import ua.monosync.widget.BalanceWidget
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val MONO_PERIODIC = "mono_periodic"
    private const val MONO_NOW = "mono_now"
    private const val NOTION_NOW = "notion_now"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            MONO_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MonoWorker>(30, TimeUnit.MINUTES).setConstraints(network).build(),
        )
    }

    fun monoNow(context: Context, delaySeconds: Long = 0, full: Boolean = false, accountId: String? = null) {
        val req = OneTimeWorkRequestBuilder<MonoWorker>()
            .setConstraints(network)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setInputData(workDataOf("full" to full, "manual" to true, "account" to accountId))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 70, TimeUnit.SECONDS)
            .build()
        val name = if (accountId == null) MONO_NOW else "mono_account_$accountId"
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
    }

    fun notionNow(context: Context, delaySeconds: Long = 0) {
        val req = OneTimeWorkRequestBuilder<NotionWorker>()
            .setConstraints(network)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOTION_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun observeMono(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(MONO_NOW)
    fun observeNotion(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NOTION_NOW)
}

class MonoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val repo = app.repo
        if (repo.secrets.monoToken.value == null) return Result.success()
        val s = repo.settings.current()
        if (!s.monoAutoSync && !inputData.getBoolean("manual", false)) return Result.success()
        val only = inputData.getString("account")
        return try {
            if (only == null) {
                repo.syncClientInfo().forEach { Notifier.newAccount(applicationContext, it) }
            }
            val full = inputData.getBoolean("full", false)
            val accounts = repo.db.accounts().all().filter { it.fromApi && !it.hidden && (only == null || it.id == only) }
            val historyStart = System.currentTimeMillis() - s.historyDays * 24 * 3600_000L
            accounts.forEachIndexed { i, acc ->
                if (i > 0) delay(61_000)
                val since = if (full || acc.lastStatementAt == 0L) historyStart else acc.lastStatementAt - 2 * 3600_000L
                repo.syncStatement(acc, since)
            }
            repo.settings.setMonoStatus(null)
            BalanceWidget.refresh(applicationContext)
            SyncScheduler.notionNow(applicationContext)
            Result.success()
        } catch (e: ApiException) {
            repo.settings.setMonoStatus(e.message)
            if (e.code == 429 && runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (e: Exception) {
            repo.settings.setMonoStatus(e.message ?: e.javaClass.simpleName)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

class NotionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        return try {
            val r = app.notion.pushPending()
            if (r.failed > 0 && runAttemptCount < 4) Result.retry() else Result.success()
        } catch (e: Exception) {
            app.repo.settings.setNotionStatus(e.message ?: e.javaClass.simpleName)
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }
}
