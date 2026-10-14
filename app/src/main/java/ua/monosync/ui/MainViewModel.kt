package ua.monosync.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.monosync.MonoSyncApp
import ua.monosync.core.Currency
import ua.monosync.data.AppSettings
import ua.monosync.data.NotionRole
import ua.monosync.data.db.AccountEntity
import ua.monosync.data.db.CategoryEntity
import ua.monosync.data.db.TransactionEntity
import ua.monosync.data.remote.NotionDataSource
import ua.monosync.sync.MonoListenerService
import ua.monosync.sync.SyncScheduler
import ua.monosync.widget.BalanceWidget
import java.time.YearMonth
import java.time.ZoneId

sealed interface Busy {
    data object Idle : Busy
    data class Working(val label: String) : Busy
}

data class NotionDiscovery(
    val sources: List<NotionDataSource> = emptyList(),
    val roles: Map<NotionRole, NotionDataSource> = emptyMap(),
    val currency: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MonoSyncApp
    private val repo = app.repo
    private val notion = app.notion

    val settings: StateFlow<AppSettings> = repo.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val accounts: StateFlow<List<AccountEntity>> = repo.accounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val categories: StateFlow<List<CategoryEntity>> = repo.categories.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val recent: StateFlow<List<TransactionEntity>> = repo.recent(2000).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pendingCount: StateFlow<Int> = repo.db.transactions().observePendingCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val rawLog = repo.db.raw().observe().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val rules = repo.db.rules().observeAll().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val monoToken = repo.secrets.monoToken
    val notionToken = repo.secrets.notionToken

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    val monthTx: StateFlow<List<TransactionEntity>> = _month.flatMapLatest { ym ->
        val zone = ZoneId.systemDefault()
        repo.range(ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(), ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val monoRunning: StateFlow<Boolean> = SyncScheduler.observeMono(app)
        .map { list -> list.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val notionRunning: StateFlow<Boolean> = SyncScheduler.observeNotion(app)
        .map { list -> list.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _listener = MutableStateFlow(MonoListenerService.isEnabled(app))
    val listenerEnabled: StateFlow<Boolean> = _listener.asStateFlow()

    private val _busy = MutableStateFlow<Busy>(Busy.Idle)
    val busy: StateFlow<Busy> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _discovery = MutableStateFlow<NotionDiscovery?>(null)
    val discovery: StateFlow<NotionDiscovery?> = _discovery.asStateFlow()

    fun consumeMessage() { _message.value = null }
    fun say(text: String) { _message.value = text }

    fun refreshPermissions() {
        _listener.value = MonoListenerService.isEnabled(app)
        if (_listener.value) MonoListenerService.requestRebind(app)
    }

    fun setMonth(ym: YearMonth) { _month.value = ym }

    private fun work(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = Busy.Working(label)
            try {
                block()
            } catch (e: Exception) {
                _message.value = e.message ?: e.javaClass.simpleName
            } finally {
                _busy.value = Busy.Idle
            }
        }
    }

    // Monobank

    fun connectMono(token: String) = work("Перевіряю токен…") {
        repo.secrets.setMonoToken(token)
        try {
            val fresh = repo.syncClientInfo()
            _message.value = if (fresh.isEmpty()) "Monobank підключено" else "Monobank підключено, знайдено карток: ${fresh.size}"
            SyncScheduler.monoNow(app, full = true)
        } catch (e: Exception) {
            repo.secrets.setMonoToken(null)
            throw e
        }
    }

    fun disconnectMono() = work("Відключаю…") {
        repo.secrets.setMonoToken(null)
        repo.settings.clearMono()
    }

    fun syncMono(full: Boolean = false) {
        if (repo.secrets.monoToken.value == null) {
            _message.value = "Спершу додай токен Monobank у налаштуваннях"
            return
        }
        SyncScheduler.monoNow(app, full = full)
        _message.value = "Синхронізація з Monobank запущена. Банк дозволяє 1 запит на хвилину, тому кілька карток займуть кілька хвилин"
    }

    fun setMonoAutoSync(v: Boolean) = viewModelScope.launch { repo.settings.setMonoAutoSync(v) }
    fun setHistoryDays(v: Int) = viewModelScope.launch { repo.settings.setHistoryDays(v) }

    // Notion

    fun discoverNotion(token: String) = work("Шукаю бази в Notion…") {
        if (token.isNotBlank()) repo.secrets.setNotionToken(token)
        val sources = notion.discover()
        if (sources.isEmpty()) {
            _message.value = "Інтеграція не бачить жодної бази. Відкрий сторінку з фінансами в Notion → ••• → Connections і додай інтеграцію"
        }
        val roles = notion.guessRoles(sources)
        _discovery.value = NotionDiscovery(sources, roles, null)
    }

    fun setRole(role: NotionRole, ds: NotionDataSource?) {
        val d = _discovery.value ?: return
        val roles = d.roles.toMutableMap()
        if (ds == null) roles.remove(role) else roles[role] = ds
        _discovery.value = d.copy(roles = roles)
    }

    fun setDiscoveryCurrency(code: String?) {
        _discovery.value = _discovery.value?.copy(currency = code)
    }

    fun previewConfig() = _discovery.value?.let { notion.buildConfig(it.roles, it.currency) }

    fun saveNotion(onDone: () -> Unit) = work("Зберігаю й підтягую рахунки та категорії…") {
        val d = _discovery.value ?: return@work
        notion.saveRoles(d.roles, d.currency)
        _discovery.value = null
        _message.value = "Notion підключено"
        SyncScheduler.notionNow(app)
        onDone()
    }

    fun cancelDiscovery() { _discovery.value = null }

    fun refreshNotionLookups() = work("Оновлюю рахунки й категорії…") {
        notion.refreshLookups()
        _message.value = "Рахунки й категорії з Notion оновлено"
    }

    fun disconnectNotion() = work("Відключаю Notion…") {
        repo.secrets.setNotionToken(null)
        repo.settings.setNotionEnabled(false)
        repo.settings.setNotionConfig(null)
    }

    fun setNotionEnabled(v: Boolean) = viewModelScope.launch {
        repo.settings.setNotionEnabled(v)
        if (v) SyncScheduler.notionNow(app)
    }

    fun setNotionIncludeTime(v: Boolean) = viewModelScope.launch { repo.settings.setNotionIncludeTime(v) }
    fun setNotionStartAt(v: Long) = viewModelScope.launch {
        repo.settings.setNotionStartAt(v)
        repo.db.accounts().all().filter { it.confirmed }.forEach { repo.db.transactions().releaseForAccount(it.id, v) }
        SyncScheduler.notionNow(app)
    }

    fun pushNotion() = viewModelScope.launch {
        repo.db.transactions().retryErrors()
        SyncScheduler.notionNow(app)
    }

    // Transactions

    fun saveTransaction(tx: TransactionEntity) = viewModelScope.launch {
        repo.saveTransaction(tx)
        afterChange()
    }

    fun setCategory(tx: TransactionEntity, categoryId: String?) = viewModelScope.launch {
        repo.setCategory(tx, categoryId, remember = true)
        afterChange()
    }

    fun addManual(tx: TransactionEntity) = viewModelScope.launch {
        repo.addManual(tx)
        afterChange()
    }

    fun deleteTransaction(tx: TransactionEntity) = viewModelScope.launch {
        repo.deleteTransaction(tx)
        afterChange()
    }

    fun resend(tx: TransactionEntity) = viewModelScope.launch {
        repo.pushToNotion(tx)
        SyncScheduler.notionNow(app)
    }

    private suspend fun afterChange() {
        SyncScheduler.notionNow(app, 2)
        BalanceWidget.refresh(app)
    }

    // Accounts

    fun confirmAccount(account: AccountEntity, name: String, currency: Currency, notionAccountId: String?) = viewModelScope.launch {
        repo.confirmAccount(account, name, currency, notionAccountId)
        if (settings.value.primaryAccountId == null && currency == Currency.UAH) repo.settings.setPrimaryAccount(account.id)
        afterChange()
    }

    fun updateAccount(account: AccountEntity) = viewModelScope.launch {
        repo.updateAccount(account)
        afterChange()
    }

    fun mergeAccounts(from: AccountEntity, into: AccountEntity) = viewModelScope.launch {
        repo.mergeAccounts(from, into)
        _message.value = "«${from.name}» об'єднано з «${into.name}»"
    }

    fun deleteAccount(account: AccountEntity) = viewModelScope.launch { repo.deleteAccount(account) }

    fun setPrimary(id: String) = viewModelScope.launch {
        repo.settings.setPrimaryAccount(id)
        BalanceWidget.refresh(app)
    }

    fun guessNotionAccount(account: AccountEntity) =
        notion.guessNotionAccount(account.name, Currency.byIso(account.currency) ?: Currency.UAH, settings.value.notionAccounts)

    // Misc

    fun setDynamicColor(v: Boolean) = viewModelScope.launch { repo.settings.setDynamicColor(v) }
    fun setNotifyOnRecord(v: Boolean) = viewModelScope.launch { repo.settings.setNotifyOnRecord(v) }
    fun deleteRule(pattern: String) = viewModelScope.launch { repo.db.rules().delete(pattern) }
    fun clearLog() = viewModelScope.launch { repo.db.raw().clear() }

}
