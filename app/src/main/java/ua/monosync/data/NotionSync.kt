package ua.monosync.data

import kotlinx.serialization.json.buildJsonObject
import ua.monosync.core.Currency
import ua.monosync.core.Mcc
import ua.monosync.core.Money
import ua.monosync.data.db.CategoryEntity
import ua.monosync.data.db.Kind
import ua.monosync.data.db.SyncState
import ua.monosync.data.db.TransactionEntity
import ua.monosync.data.db.cur
import ua.monosync.data.remote.ApiException
import ua.monosync.data.remote.NotionApi
import ua.monosync.data.remote.NotionDataSource
import ua.monosync.data.remote.NotionProperty
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class NotionRole(val title: String, val hints: List<String>) {
    EXPENSES("Витрати", listOf("expense", "витрат", "spend")),
    INCOMES("Доходи", listOf("income", "дох", "надходж", "earning")),
    TRANSFERS("Перекази", listOf("transfer", "переказ")),
    ACCOUNTS("Рахунки", listOf("account", "рахун", "гаманц", "wallet")),
    CATEGORIES("Категорії", listOf("categor", "катег")),
}

class NotionSync(private val repo: Repository) {
    val api = NotionApi { repo.secrets.notionToken.value }
    private val db get() = repo.db
    private val settings get() = repo.settings

    suspend fun discover(): List<NotionDataSource> = api.searchDataSources()

    fun guessRoles(sources: List<NotionDataSource>): Map<NotionRole, NotionDataSource> {
        val out = mutableMapOf<NotionRole, NotionDataSource>()
        for (role in NotionRole.entries) {
            sources.firstOrNull { ds ->
                ds !in out.values && role.hints.any { ds.title.lowercase().contains(it) } && fits(role, ds)
            }?.let { out[role] = it }
        }
        return out
    }

    private fun fits(role: NotionRole, ds: NotionDataSource): Boolean = when (role) {
        NotionRole.ACCOUNTS, NotionRole.CATEGORIES -> true
        else -> ds.properties.any { it.type == "number" }
    }

    fun buildConfig(roles: Map<NotionRole, NotionDataSource>, currencyOverride: String?): NotionConfig {
        val accounts = roles[NotionRole.ACCOUNTS]
        val categories = roles[NotionRole.CATEGORIES]

        fun relationTo(p: NotionProperty, ds: NotionDataSource?) = ds != null && p.type == "relation" &&
            (p.relationDataSourceId?.let { sameId(it, ds.id) } == true ||
                (p.relationDatabaseId != null && ds.databaseId != null && sameId(p.relationDatabaseId, ds.databaseId)))

        fun target(ds: NotionDataSource?, transfer: Boolean = false): NotionTarget? {
            ds ?: return null
            val title = ds.properties.firstOrNull { it.type == "title" }?.name ?: return null
            val numbers = ds.properties.filter { it.type == "number" }
            val amount = numbers.firstOrNull { n -> listOf("сума", "amount", "sum", "сумма", "value").any { n.name.lowercase().contains(it) } }
                ?: numbers.firstOrNull()
            val dates = ds.properties.filter { it.type == "date" }
            val date = dates.firstOrNull { n -> listOf("дата", "date", "день").any { n.name.lowercase().contains(it) } } ?: dates.firstOrNull()
            val accRels = ds.properties.filter { relationTo(it, accounts) }
            val catRel = ds.properties.firstOrNull { relationTo(it, categories) }
            val (from, to) = if (transfer) {
                val f = accRels.firstOrNull { r -> Regex("(^з\\b|^з |from|звідки|з рах|source)", RegexOption.IGNORE_CASE).containsMatchIn(r.name) }
                    ?: accRels.getOrNull(0)
                val t = accRels.firstOrNull { r -> r != f && Regex("(^на\\b|^на |to\\b|куди|на рах|target|dest)", RegexOption.IGNORE_CASE).containsMatchIn(r.name) }
                    ?: accRels.firstOrNull { it != f }
                f to t
            } else accRels.firstOrNull() to null
            return NotionTarget(ds.id, ds.title, title, amount?.name, date?.name, from?.name, to?.name, catRel?.name)
        }

        val expenses = target(roles[NotionRole.EXPENSES])
        val format = roles[NotionRole.EXPENSES]?.properties?.firstOrNull { it.name == expenses?.amountProp }?.numberFormat
        val currency = currencyOverride ?: when (format) {
            "euro" -> "EUR"
            "dollar" -> "USD"
            "pound" -> "GBP"
            "zloty" -> "PLN"
            "hryvnia" -> "UAH"
            else -> "UAH"
        }
        return NotionConfig(
            currency = currency,
            expenses = expenses,
            incomes = target(roles[NotionRole.INCOMES]),
            transfers = target(roles[NotionRole.TRANSFERS], transfer = true),
            accountsDsId = accounts?.id,
            accountsName = accounts?.title,
            categoriesDsId = categories?.id,
            categoriesName = categories?.title,
            categoriesBudgetProp = categories?.properties?.filter { it.type == "number" }
                ?.let { nums -> nums.firstOrNull { n -> listOf("бюджет", "budget", "ліміт", "limit").any { n.name.lowercase().contains(it) } } ?: nums.firstOrNull() }
                ?.name,
        )
    }

    private fun sameId(a: String, b: String) = a.replace("-", "").equals(b.replace("-", ""), true)

    /** Pulls accounts and categories from Notion, so the app can use them. */
    suspend fun refreshLookups() {
        val config = settings.current().notionConfig ?: return
        config.accountsDsId?.let { id ->
            val rows = api.queryAll(id)
            settings.setNotionAccounts(rows.map { NotionAccount(it.id, it.title) })
            autoMapAccounts(rows.map { NotionAccount(it.id, it.title) })
        }
        config.categoriesDsId?.let { id ->
            val budgetProp = config.categoriesBudgetProp
            val rows = api.queryAll(id, listOfNotNull(budgetProp))
            val cats = rows.mapIndexed { i, r ->
                val group = Mcc.groups.firstOrNull { g -> g.title.equals(r.title, true) || g.keywords.any { r.title.lowercase().contains(it) } }
                CategoryEntity(
                    id = r.id,
                    name = r.title,
                    budget = budgetProp?.let { r.numbers[it] }?.let { Math.round(it * 100) },
                    groupKey = group?.key,
                    fromNotion = true,
                    sortOrder = i,
                )
            }
            if (cats.isNotEmpty()) {
                val old = db.categories().all().filter { it.fromNotion }.map { it.id }.toSet() - cats.map { it.id }.toSet()
                old.forEach { db.categories().delete(it) }
                db.categories().upsert(cats)
                remapLocalCategories(cats)
                db.categories().deleteBySource(false)
            }
        }
    }

    private suspend fun remapLocalCategories(cats: List<CategoryEntity>) {
        db.transactions().withLocalCategory().forEach { tx ->
            val key = tx.categoryId?.removePrefix("local:")
            val group = Mcc.groups.firstOrNull { it.key == key }
            val target = cats.firstOrNull { it.groupKey == key } ?: Mcc.matchCategory(group, cats) { it.name }
            db.transactions().update(tx.copy(categoryId = target?.id))
        }
    }

    private suspend fun autoMapAccounts(notionAccounts: List<NotionAccount>) {
        if (notionAccounts.isEmpty()) return
        db.accounts().all().filter { it.notionAccountId == null }.forEach { acc ->
            val match = guessNotionAccount(acc.name, acc.cur, notionAccounts)
            if (match != null) db.accounts().upsert(acc.copy(notionAccountId = match.id))
        }
    }

    fun guessNotionAccount(name: String, currency: Currency, list: List<NotionAccount>): NotionAccount? {
        val n = name.lowercase()
        val monoLike = list.filter { it.name.lowercase().contains("mono") || it.name.lowercase().contains("моно") }
        val bySymbol = monoLike.filter { it.name.contains(currency.symbol) || it.name.uppercase().contains(currency.code) }
        if (bySymbol.size == 1) return bySymbol.first()
        val firstWord = n.split(" ").firstOrNull()?.take(4)
        if (firstWord != null && firstWord.length >= 3) {
            list.firstOrNull { it.name.lowercase().contains(firstWord) }?.let { return it }
        }
        if (currency == Currency.UAH) {
            val plain = monoLike.filter { a -> Currency.entries.none { c -> c != Currency.UAH && (a.name.contains(c.symbol) || a.name.uppercase().contains(c.code)) } }
            if (plain.size == 1) return plain.first()
        }
        return null
    }

    // Push

    data class PushResult(val sent: Int, val failed: Int, val lastError: String?)

    suspend fun pushPending(): PushResult {
        val s = settings.current()
        val config = s.notionConfig ?: return PushResult(0, 0, null)
        if (!s.notionEnabled || repo.secrets.notionToken.value == null) return PushResult(0, 0, null)
        var sent = 0
        var failed = 0
        var lastError: String? = null
        for (tx in db.transactions().pendingSync()) {
            try {
                if (pushOne(tx, config, s)) sent++
            } catch (e: Exception) {
                failed++
                lastError = (e as? ApiException)?.message ?: e.message ?: e.javaClass.simpleName
                db.transactions().update(tx.copy(syncState = SyncState.ERROR, syncError = lastError))
                if (e is ApiException && (e.code == 401 || e.code == 429)) break
            }
        }
        settings.setNotionStatus(lastError)
        return PushResult(sent, failed, lastError)
    }

    private suspend fun pushOne(tx: TransactionEntity, config: NotionConfig, s: AppSettings): Boolean {
        val target = when (tx.kind) {
            Kind.INCOME -> config.incomes
            Kind.TRANSFER -> config.transfers ?: if (tx.amount < 0) config.expenses else config.incomes
            else -> config.expenses
        }
        if (tx.deleted || target == null) {
            tx.notionPageId?.let { api.trashPage(it) }
            db.transactions().update(tx.copy(notionPageId = null, notionDb = null, syncState = SyncState.SKIPPED, syncError = if (target == null && !tx.deleted) "Немає бази для цього типу" else null))
            return tx.notionPageId != null
        }
        val account = db.accounts().byId(tx.accountId)
        if (account == null || !account.confirmed) {
            db.transactions().update(tx.copy(syncState = SyncState.SKIPPED))
            return false
        }
        val toAccount = tx.transferAccountId?.let { db.accounts().byId(it) }
        val amount = notionAmount(tx, Currency.byCode(config.currency) ?: Currency.UAH)

        val props = buildJsonObject {
            put(target.titleProp, NotionApi.title(tx.description))
            target.amountProp?.let { put(it, NotionApi.number(amount)) }
            target.dateProp?.let { put(it, NotionApi.date(formatDate(tx.time, s.notionIncludeTime))) }
            if (tx.kind == Kind.TRANSFER && target == config.transfers) {
                val from = if (tx.amount < 0) account else toAccount
                val to = if (tx.amount < 0) toAccount else account
                target.accountProp?.let { put(it, NotionApi.relation(from?.notionAccountId)) }
                target.toAccountProp?.let { put(it, NotionApi.relation(to?.notionAccountId)) }
            } else {
                target.accountProp?.let { put(it, NotionApi.relation(account.notionAccountId)) }
            }
            if (tx.kind == Kind.EXPENSE) {
                target.categoryProp?.let { put(it, NotionApi.relation(tx.categoryId?.takeIf { id -> !id.startsWith("local:") })) }
            }
        }

        val pageId = if (tx.notionPageId != null && tx.notionDb == target.dataSourceId) {
            try {
                api.updatePage(tx.notionPageId, props)
                tx.notionPageId
            } catch (e: ApiException) {
                if (e.code != 404) throw e
                api.createPage(target.dataSourceId, props, details(tx))
            }
        } else {
            tx.notionPageId?.let { runCatching { api.trashPage(it) } }
            api.createPage(target.dataSourceId, props, details(tx))
        }
        db.transactions().update(tx.copy(notionPageId = pageId, notionDb = target.dataSourceId, syncState = SyncState.SYNCED, syncError = null))
        return true
    }

    private fun details(tx: TransactionEntity) = NotionApi.blocks(
        NotionApi.paragraph(
            buildString {
                append("MonoSync · ")
                append(Money.format(tx.amount, tx.cur, signed = true))
                if (tx.operationAmount != null && tx.operationCurrency != null) {
                    append(" (").append(Money.format(tx.operationAmount, Currency.byIso(tx.operationCurrency), signed = true)).append(")")
                }
                tx.mcc?.let { append(" · MCC ").append(it) }
                tx.comment?.let { append("\n").append(it) }
            }
        )
    )

    private fun formatDate(time: Long, withTime: Boolean): String {
        val zone = ZoneId.systemDefault()
        val inst = Instant.ofEpochMilli(time)
        return if (withTime) DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(inst.atZone(zone).truncatedTo(ChronoUnit.MINUTES))
        else DateTimeFormatter.ISO_LOCAL_DATE.format(inst.atZone(zone))
    }

    /** Amount in the currency the Notion workspace keeps its books in, always positive. */
    suspend fun notionAmount(tx: TransactionEntity, target: Currency): Double {
        val minor = when {
            tx.cur == target -> abs(tx.amount)
            tx.operationCurrency == target.iso && tx.operationAmount != null -> abs(tx.operationAmount)
            else -> convert(abs(tx.amount), tx.cur, target)
        }
        return minor / 100.0
    }

    suspend fun convert(minor: Long, from: Currency, to: Currency): Long {
        if (from == to) return minor
        val rates = loadRates()
        fun uahPer(c: Currency): Double? {
            if (c == Currency.UAH) return 1.0
            val r = rates.items.firstOrNull { it.a == c.iso && it.b == Currency.UAH.iso } ?: return null
            return r.cross ?: r.sell?.let { s -> r.buy?.let { b -> (s + b) / 2 } ?: s } ?: r.buy
        }
        val f = uahPer(from) ?: return minor
        val t = uahPer(to) ?: return minor
        return Math.round(minor * f / t)
    }

    private suspend fun loadRates(): Rates {
        val cached = settings.rates()
        if (cached != null && System.currentTimeMillis() - cached.at < 6 * 3600_000L) return cached
        return try {
            val fresh = repo.mono.rates().map { Rate(it.currencyCodeA, it.currencyCodeB, it.rateBuy, it.rateSell, it.rateCross) }
            Rates(System.currentTimeMillis(), fresh).also { settings.setRates(it) }
        } catch (e: Exception) {
            cached ?: Rates(0, listOf(Rate(978, 980, cross = 48.0), Rate(840, 980, cross = 41.5)))
        }
    }

    suspend fun saveRoles(roles: Map<NotionRole, NotionDataSource>, currencyOverride: String?) {
        settings.setNotionConfig(buildConfig(roles, currencyOverride))
        settings.setNotionEnabled(true)
        refreshLookups()
        val startAt = settings.current().notionStartAt
        db.accounts().all().filter { it.confirmed }.forEach { db.transactions().releaseForAccount(it.id, startAt) }
    }
}
