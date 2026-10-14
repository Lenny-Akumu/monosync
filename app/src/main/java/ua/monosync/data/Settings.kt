package ua.monosync.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("settings")

@Serializable
data class NotionTarget(
    val dataSourceId: String,
    val name: String,
    val titleProp: String,
    val amountProp: String? = null,
    val dateProp: String? = null,
    val accountProp: String? = null,
    val toAccountProp: String? = null,
    val categoryProp: String? = null,
)

@Serializable
data class NotionConfig(
    val currency: String = "EUR",
    val expenses: NotionTarget? = null,
    val incomes: NotionTarget? = null,
    val transfers: NotionTarget? = null,
    val accountsDsId: String? = null,
    val accountsName: String? = null,
    val categoriesDsId: String? = null,
    val categoriesName: String? = null,
    val categoriesBudgetProp: String? = null,
)

@Serializable
data class NotionAccount(val id: String, val name: String)

@Serializable
data class Rates(val at: Long, val items: List<Rate>)

@Serializable
data class Rate(val a: Int, val b: Int, val buy: Double? = null, val sell: Double? = null, val cross: Double? = null)

data class AppSettings(
    val notionEnabled: Boolean = false,
    val notionConfig: NotionConfig? = null,
    val notionAccounts: List<NotionAccount> = emptyList(),
    val notionStartAt: Long = 0,
    val notionIncludeTime: Boolean = false,
    val notionLastSync: Long = 0,
    val notionLastError: String? = null,
    val monoAutoSync: Boolean = true,
    val monoLastSync: Long = 0,
    val monoLastError: String? = null,
    val monoClientName: String? = null,
    val dynamicColor: Boolean = true,
    val primaryAccountId: String? = null,
    val notifyOnRecord: Boolean = false,
    val historyDays: Int = 7,
)

class Settings(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object K {
        val notionEnabled = booleanPreferencesKey("notion_enabled")
        val notionConfig = stringPreferencesKey("notion_config")
        val notionAccounts = stringPreferencesKey("notion_accounts")
        val notionStartAt = longPreferencesKey("notion_start_at")
        val notionIncludeTime = booleanPreferencesKey("notion_include_time")
        val notionLastSync = longPreferencesKey("notion_last_sync")
        val notionLastError = stringPreferencesKey("notion_last_error")
        val monoAutoSync = booleanPreferencesKey("mono_auto_sync")
        val monoLastSync = longPreferencesKey("mono_last_sync")
        val monoLastError = stringPreferencesKey("mono_last_error")
        val monoClientName = stringPreferencesKey("mono_client_name")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val primaryAccount = stringPreferencesKey("primary_account")
        val notifyOnRecord = booleanPreferencesKey("notify_on_record")
        val rates = stringPreferencesKey("rates")
        val historyDays = longPreferencesKey("history_days")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p -> read(p) }

    suspend fun current(): AppSettings = flow.first()

    private fun read(p: Preferences) = AppSettings(
        notionEnabled = p[K.notionEnabled] ?: false,
        notionConfig = p[K.notionConfig]?.let { runCatching { json.decodeFromString<NotionConfig>(it) }.getOrNull() },
        notionAccounts = p[K.notionAccounts]?.let { runCatching { json.decodeFromString<List<NotionAccount>>(it) }.getOrNull() }
            ?: emptyList(),
        notionStartAt = p[K.notionStartAt] ?: 0,
        notionIncludeTime = p[K.notionIncludeTime] ?: false,
        notionLastSync = p[K.notionLastSync] ?: 0,
        notionLastError = p[K.notionLastError],
        monoAutoSync = p[K.monoAutoSync] ?: true,
        monoLastSync = p[K.monoLastSync] ?: 0,
        monoLastError = p[K.monoLastError],
        monoClientName = p[K.monoClientName],
        dynamicColor = p[K.dynamicColor] ?: true,
        primaryAccountId = p[K.primaryAccount],
        notifyOnRecord = p[K.notifyOnRecord] ?: false,
        historyDays = (p[K.historyDays] ?: 7L).toInt(),
    )

    suspend fun setNotionEnabled(v: Boolean) = context.dataStore.edit {
        it[K.notionEnabled] = v
        if (v && (it[K.notionStartAt] ?: 0L) == 0L) it[K.notionStartAt] = System.currentTimeMillis() - 60 * 60 * 1000L
    }

    suspend fun setNotionConfig(c: NotionConfig?) = context.dataStore.edit {
        if (c == null) it.remove(K.notionConfig) else { it[K.notionConfig] = json.encodeToString(c) }
    }

    suspend fun setNotionAccounts(list: List<NotionAccount>) = context.dataStore.edit {
        it[K.notionAccounts] = json.encodeToString(list)
    }

    suspend fun setNotionStartAt(v: Long) = context.dataStore.edit { it[K.notionStartAt] = v }
    suspend fun setNotionIncludeTime(v: Boolean) = context.dataStore.edit { it[K.notionIncludeTime] = v }
    suspend fun setNotionStatus(error: String?) = context.dataStore.edit {
        if (error == null) {
            it[K.notionLastSync] = System.currentTimeMillis()
            it.remove(K.notionLastError)
        } else { it[K.notionLastError] = error }
    }

    suspend fun setMonoAutoSync(v: Boolean) = context.dataStore.edit { it[K.monoAutoSync] = v }
    suspend fun setMonoStatus(error: String?, clientName: String? = null) = context.dataStore.edit {
        if (error == null) {
            it[K.monoLastSync] = System.currentTimeMillis()
            it.remove(K.monoLastError)
        } else { it[K.monoLastError] = error }
        if (clientName != null) it[K.monoClientName] = clientName
    }

    suspend fun clearMono() = context.dataStore.edit {
        it.remove(K.monoClientName); it.remove(K.monoLastError); it.remove(K.monoLastSync)
    }

    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[K.dynamicColor] = v }
    suspend fun setPrimaryAccount(id: String?) = context.dataStore.edit {
        if (id == null) it.remove(K.primaryAccount) else { it[K.primaryAccount] = id }
    }
    suspend fun setNotifyOnRecord(v: Boolean) = context.dataStore.edit { it[K.notifyOnRecord] = v }
    suspend fun setHistoryDays(v: Int) = context.dataStore.edit { it[K.historyDays] = v.toLong() }

    suspend fun rates(): Rates? = context.dataStore.data.first()[K.rates]
        ?.let { runCatching { json.decodeFromString<Rates>(it) }.getOrNull() }

    suspend fun setRates(r: Rates) = context.dataStore.edit { it[K.rates] = json.encodeToString(r) }
}

class Secrets(context: Context) {
    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "secrets", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { context.getSharedPreferences("secrets_plain", Context.MODE_PRIVATE) }

    private val _monoToken = MutableStateFlow(prefs.getString("mono", null))
    private val _notionToken = MutableStateFlow(prefs.getString("notion", null))
    val monoToken: StateFlow<String?> = _monoToken.asStateFlow()
    val notionToken: StateFlow<String?> = _notionToken.asStateFlow()

    fun setMonoToken(v: String?) {
        prefs.edit().apply { if (v.isNullOrBlank()) remove("mono") else putString("mono", v.trim()) }.apply()
        _monoToken.value = v?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setNotionToken(v: String?) {
        prefs.edit().apply { if (v.isNullOrBlank()) remove("notion") else putString("notion", v.trim()) }.apply()
        _notionToken.value = v?.trim()?.takeIf { it.isNotEmpty() }
    }
}
