package ua.monosync

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ua.monosync.data.NotionSync
import ua.monosync.data.Repository
import ua.monosync.data.Secrets
import ua.monosync.data.Settings
import ua.monosync.data.db.AppDatabase
import ua.monosync.sync.Notifier
import ua.monosync.sync.SyncScheduler

class MonoSyncApp : Application() {
    lateinit var repo: Repository
        private set
    lateinit var notion: NotionSync
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this, AppDatabase.create(this), Settings(this), Secrets(this))
        notion = NotionSync(repo)
        Notifier.createChannels(this)
        scope.launch {
            repo.ensureLocalCategories()
            runCatching { repo.refreshSubscriptions() }
            SyncScheduler.schedulePeriodic(this@MonoSyncApp)
        }
    }
}

val Context.app: MonoSyncApp get() = applicationContext as MonoSyncApp
