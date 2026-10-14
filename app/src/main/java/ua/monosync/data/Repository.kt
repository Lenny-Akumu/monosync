package ua.monosync.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ua.monosync.core.CardKeys
import ua.monosync.core.Currency
import ua.monosync.core.Direction
import ua.monosync.core.Mcc
import ua.monosync.core.NotificationParser
import ua.monosync.data.db.AccountEntity
import ua.monosync.data.db.AppDatabase
import ua.monosync.data.db.CategoryEntity
import ua.monosync.data.db.Kind
import ua.monosync.data.db.RawNotificationEntity
import ua.monosync.data.db.RuleEntity
import ua.monosync.data.db.Source
import ua.monosync.data.db.SyncState
import ua.monosync.data.db.TransactionEntity
import ua.monosync.data.db.cur
import ua.monosync.data.remote.MonoAccount
import ua.monosync.data.remote.MonoApi
import ua.monosync.data.remote.MonoStatementItem
import java.security.MessageDigest
import kotlin.math.abs

class Repository(
    private val context: Context,
    val db: AppDatabase,
    val settings: Settings,
    val secrets: Secrets,
) {
    val mono = MonoApi { secrets.monoToken.value }
    private val lock = Mutex()

    val accounts: Flow<List<AccountEntity>> = db.accounts().observeAll()
    val categories: Flow<List<CategoryEntity>> = db.categories().observeAll()

    fun recent(limit: Int = 300) = db.transactions().observeRecent(limit)
    fun range(from: Long, to: Long) = db.transactions().observeRange(from, to)

    sealed interface IngestResult {
        data class Recorded(val tx: TransactionEntity, val account: AccountEntity, val newAccount: Boolean) : IngestResult
        data object Ignored : IngestResult
        data object Duplicate : IngestResult
    }

    suspend fun ensureLocalCategories() {
        if (db.categories().all().isNotEmpty()) return
        db.categories().upsert(Mcc.groups.mapIndexed { i, g ->
            CategoryEntity(id = "local:${g.key}", name = g.title, groupKey = g.key, sortOrder = i)
        })
    }

    suspend fun ingestNotification(key: String, postedAt: Long, title: String?, text: String?): IngestResult = lock.withLock {
        val hash = sha("$title|$text|${postedAt / (6 * 3600_000L)}")
        val rawId = db.raw().insert(RawNotificationEntity(hash = hash, postedAt = postedAt, title = title, text = text, parsed = false))
        if (rawId == -1L) return IngestResult.Duplicate

        val parsed = NotificationParser.parse(title, text) ?: return IngestResult.Ignored
        val settingsNow = settings.current()

        var newAccount = false
        val account = run {
            val cardKey = parsed.cardKey
            val existing = cardKey?.let { db.accounts().byKey(it) }.orEmpty()
            existing.firstOrNull { it.confirmed } ?: existing.firstOrNull() ?: run {
                val all = db.accounts().all()
                if (cardKey == null) {
                    all.firstOrNull { it.id == settingsNow.primaryAccountId }
                        ?: all.firstOrNull { it.confirmed && it.cur == (parsed.currency ?: Currency.UAH) }
                        ?: all.firstOrNull { it.confirmed }
                } else null
            } ?: run {
                newAccount = true
                val k = cardKey ?: "картк"
                val currency = CardKeys.guessCurrency(k) ?: parsed.currency ?: Currency.UAH
                AccountEntity(
                    id = "card:$k",
                    name = parsed.cardPhrase?.let { CardKeys.displayName(it) } ?: "Картка Mono",
                    cardKey = k,
                    type = CardKeys.guessType(k),
                    currency = currency.iso,
                    confirmed = false,
                ).also { db.accounts().upsert(it) }
            }
        }

        val amountAbs = parsed.accountAmountMinor(account.cur)
        val signed = if (parsed.direction == Direction.IN) amountAbs else -amountAbs
        val kind = if (signed > 0) Kind.INCOME else Kind.EXPENSE
        val description = parsed.description
            ?: parsed.jarName?.let { "Банка «$it»" }
            ?: if (signed > 0) "Поповнення" else "Переказ з картки"
        val operation = parsed.currency?.takeIf { it != account.cur }

        val tx = TransactionEntity(
            accountId = account.id,
            time = postedAt,
            amount = signed,
            currency = account.currency,
            operationAmount = operation?.let { if (signed > 0) parsed.amountMinor else -parsed.amountMinor },
            operationCurrency = operation?.iso,
            description = description,
            genericDescription = parsed.description == null,
            categoryId = if (kind == Kind.EXPENSE) pickCategory(description, null) else null,
            kind = kind,
            balanceAfter = parsed.balanceMinor,
            comment = parsed.notes.joinToString("\n").ifBlank { null },
            source = Source.NOTIFICATION,
            rawText = listOfNotNull(title, text).joinToString("\n"),
            syncState = initialSyncState(account, postedAt, settingsNow),
        )
        val id = db.transactions().insert(tx)
        db.raw().mark(rawId, true, id)
        if (parsed.balanceMinor != null) {
            db.accounts().upsert(account.copy(balance = parsed.balanceMinor, updatedAt = postedAt))
        }
        val saved = db.transactions().byId(id) ?: tx.copy(id = id)
        val merged = mergeOwnTransfer(saved)
        IngestResult.Recorded(merged, db.accounts().byId(account.id) ?: account, newAccount)
    }

    private fun initialSyncState(account: AccountEntity, time: Long, s: AppSettings): String =
        if (!account.confirmed || time < s.notionStartAt) SyncState.SKIPPED else SyncState.PENDING

    /** Two opposite operations on two own cards within two minutes are one transfer. */
    private suspend fun mergeOwnTransfer(tx: TransactionEntity): TransactionEntity {
        if (tx.kind == Kind.TRANSFER) return tx
        val window = 3 * 60_000L
        val twin = db.transactions().around(tx.time - window, tx.time + window).firstOrNull {
            it.id != tx.id && it.accountId != tx.accountId && it.amount != 0L &&
                (it.amount > 0) != (tx.amount > 0) && amountsClose(it, tx)
        } ?: return tx
        val out = if (tx.amount < 0) tx else twin
        val inc = if (tx.amount < 0) twin else tx
        val description = when {
            !out.genericDescription -> out.description
            !inc.genericDescription -> inc.description
            else -> "Переказ між картками"
        }
        val transfer = out.copy(
            kind = Kind.TRANSFER,
            transferAccountId = inc.accountId,
            description = description,
            categoryId = null,
            syncState = if (out.syncState == SyncState.SKIPPED && inc.syncState == SyncState.SKIPPED) SyncState.SKIPPED else SyncState.PENDING,
        )
        db.transactions().update(transfer)
        db.transactions().update(
            inc.copy(deleted = true, syncState = if (inc.notionPageId != null) SyncState.PENDING else SyncState.SKIPPED)
        )
        return transfer
    }

    private fun amountsClose(a: TransactionEntity, b: TransactionEntity): Boolean {
        if (a.currency == b.currency) return abs(a.amount) == abs(b.amount)
        val ao = a.operationAmount?.let { abs(it) }
        val bo = b.operationAmount?.let { abs(it) }
        return (ao != null && ao == abs(b.amount)) || (bo != null && bo == abs(a.amount))
    }

    suspend fun pickCategory(description: String, mcc: Int?): String? {
        val desc = description.lowercase().trim()
        db.rules().all().firstOrNull { desc.contains(it.pattern) }?.let { return it.categoryId }
        val cats = db.categories().all()
        val group = Mcc.groupForMerchant(desc) ?: Mcc.group(mcc)
        return Mcc.matchCategory(group, cats) { it.name }?.id
            ?: group?.let { g -> cats.firstOrNull { it.groupKey == g.key }?.id }
    }

    suspend fun setCategory(tx: TransactionEntity, categoryId: String?, remember: Boolean) {
        db.transactions().update(tx.copy(categoryId = categoryId, userEdited = true, syncState = resync(tx)))
        if (remember && categoryId != null) {
            val pattern = tx.description.lowercase().trim()
            if (pattern.length >= 3 && !tx.genericDescription) {
                db.rules().upsert(RuleEntity(pattern, categoryId))
                db.transactions().uncategorizedByDescription(pattern).forEach {
                    db.transactions().update(it.copy(categoryId = categoryId, syncState = resync(it)))
                }
            }
        }
    }

    fun resync(tx: TransactionEntity): String = when (tx.syncState) {
        SyncState.SKIPPED -> if (tx.notionPageId != null) SyncState.PENDING else SyncState.SKIPPED
        else -> SyncState.PENDING
    }

    suspend fun saveTransaction(tx: TransactionEntity) {
        db.transactions().update(tx.copy(userEdited = true, syncState = resync(tx)))
    }

    suspend fun addManual(tx: TransactionEntity): Long {
        val s = settings.current()
        val acc = db.accounts().byId(tx.accountId)
        val state = if (acc?.confirmed == true && tx.time >= s.notionStartAt) SyncState.PENDING else SyncState.SKIPPED
        return db.transactions().insert(tx.copy(source = Source.MANUAL, syncState = state, userEdited = true))
    }

    suspend fun deleteTransaction(tx: TransactionEntity) {
        db.transactions().update(
            tx.copy(deleted = true, syncState = if (tx.notionPageId != null) SyncState.PENDING else SyncState.SKIPPED)
        )
    }

    suspend fun pushToNotion(tx: TransactionEntity) {
        db.transactions().update(tx.copy(syncState = SyncState.PENDING, syncError = null))
    }

    // Accounts

    suspend fun confirmAccount(account: AccountEntity, name: String, currency: Currency, notionAccountId: String?) {
        val updated = account.copy(name = name.trim().ifBlank { account.name }, currency = currency.iso, confirmed = true, notionAccountId = notionAccountId)
        db.accounts().upsert(updated)
        db.transactions().releaseForAccount(account.id, settings.current().notionStartAt)
    }

    suspend fun updateAccount(account: AccountEntity) = db.accounts().upsert(account)

    suspend fun mergeAccounts(from: AccountEntity, into: AccountEntity) {
        db.transactions().moveAccount(from.id, into.id)
        db.transactions().moveTransferAccount(from.id, into.id)
        db.accounts().delete(from.id)
    }

    suspend fun deleteAccount(account: AccountEntity) = db.accounts().delete(account.id)

    // Monobank API

    suspend fun syncClientInfo(): List<AccountEntity> {
        val info = mono.clientInfo()
        settings.setMonoStatus(null, info.name)
        val known = db.accounts().all()
        val newOnes = mutableListOf<AccountEntity>()
        info.accounts.forEachIndexed { index, a -> upsertApiAccount(a, index, known)?.let(newOnes::add) }
        return newOnes
    }

    private suspend fun upsertApiAccount(a: MonoAccount, index: Int, known: List<AccountEntity>): AccountEntity? {
        val currency = Currency.byIso(a.currencyCode)
        val key = CardKeys.keyForApi(a.type, currency)
        val own = a.balance - a.creditLimit
        val byId = known.firstOrNull { it.id == a.id }
        if (byId != null) {
            db.accounts().upsert(byId.copy(balance = own, creditLimit = a.creditLimit, maskedPan = a.maskedPan.firstOrNull(), iban = a.iban, updatedAt = System.currentTimeMillis()))
            return null
        }
        val placeholder = known.firstOrNull { !it.fromApi && it.cardKey == key && it.currency == a.currencyCode }
        val typeName = CardKeys.typeTitle(a.type)
        val name = placeholder?.name ?: when {
            currency == Currency.EUR -> "Єврова картка"
            currency == Currency.USD -> "Доларова картка"
            typeName != null -> "$typeName картка"
            else -> "Картка ${a.maskedPan.firstOrNull()?.takeLast(4) ?: ""}".trim()
        }
        val entity = AccountEntity(
            id = a.id,
            name = name,
            cardKey = key,
            type = a.type,
            currency = a.currencyCode,
            maskedPan = a.maskedPan.firstOrNull(),
            iban = a.iban,
            balance = own,
            creditLimit = a.creditLimit,
            confirmed = placeholder?.confirmed ?: false,
            fromApi = true,
            notionAccountId = placeholder?.notionAccountId,
            sortOrder = index,
        )
        db.accounts().upsert(entity)
        if (placeholder != null) {
            db.transactions().moveAccount(placeholder.id, entity.id)
            db.transactions().moveTransferAccount(placeholder.id, entity.id)
            db.accounts().delete(placeholder.id)
            return null
        }
        return entity
    }

    /** Pulls one account's statement and lines it up with what notifications already recorded. */
    suspend fun syncStatement(account: AccountEntity, sinceMillis: Long): Int = lock.withLock {
        val now = System.currentTimeMillis()
        val from = maxOf(sinceMillis, now - 31L * 24 * 3600 * 1000 + 3600_000)
        val items = mono.statement(account.id, from / 1000, now / 1000)
        val s = settings.current()
        var created = 0
        for (item in items.sortedBy { it.time }) {
            if (applyStatementItem(account, item, s)) created++
        }
        db.accounts().byId(account.id)?.let { db.accounts().upsert(it.copy(lastStatementAt = now)) }
        created
    }

    private suspend fun applyStatementItem(account: AccountEntity, item: MonoStatementItem, s: AppSettings): Boolean {
        val time = item.time * 1000
        val balanceOwn = item.balance?.let { it - account.creditLimit }
        val opCurrency = item.currencyCode?.takeIf { it != account.currency }
        val existing = db.transactions().byMonoId(item.id)
        if (existing != null) {
            if (!existing.userEdited) {
                val upd = existing.copy(
                    mcc = item.mcc ?: existing.mcc,
                    balanceAfter = balanceOwn ?: existing.balanceAfter,
                    amount = if (existing.kind == Kind.TRANSFER) existing.amount else item.amount,
                )
                if (upd != existing) db.transactions().update(upd)
            }
            return false
        }

        val candidates = db.transactions().unmatched(account.id, time - 15 * 60_000L, time + 15 * 60_000L)
            .filter { c ->
                val tol = maxOf(2L, abs(item.amount) / 100)
                (c.amount > 0) == (item.amount > 0) &&
                    (abs(abs(c.amount) - abs(item.amount)) <= tol ||
                        (item.operationAmount != null && c.operationAmount != null && abs(c.operationAmount) == abs(item.operationAmount)))
            }
            .sortedBy { abs(it.time - time) }
        val match = candidates.firstOrNull()
        val apiDescription = item.description.trim().ifBlank { null }

        if (match != null) {
            val takeDescription = match.genericDescription && apiDescription != null && !match.userEdited
            val newDesc = if (takeDescription) apiDescription!! else match.description
            val category = match.categoryId
                ?: if (match.kind == Kind.EXPENSE) pickCategory(newDesc, item.mcc) else null
            db.transactions().update(
                match.copy(
                    monoId = item.id,
                    mcc = item.mcc,
                    amount = if (match.kind == Kind.TRANSFER) match.amount else item.amount,
                    operationAmount = if (opCurrency != null) item.operationAmount else match.operationAmount,
                    operationCurrency = opCurrency ?: match.operationCurrency,
                    description = newDesc,
                    genericDescription = if (takeDescription) false else match.genericDescription,
                    balanceAfter = balanceOwn ?: match.balanceAfter,
                    categoryId = category,
                    syncState = if (takeDescription || category != match.categoryId) resync(match) else match.syncState,
                )
            )
            return false
        }

        val description = apiDescription ?: if (item.amount > 0) "Поповнення" else "Списання"
        val kind = if (item.amount > 0) Kind.INCOME else Kind.EXPENSE
        val tx = TransactionEntity(
            monoId = item.id,
            accountId = account.id,
            time = time,
            amount = item.amount,
            currency = account.currency,
            operationAmount = if (opCurrency != null) item.operationAmount else null,
            operationCurrency = opCurrency,
            description = description,
            mcc = item.mcc,
            categoryId = if (kind == Kind.EXPENSE) pickCategory(description, item.mcc) else null,
            kind = kind,
            balanceAfter = balanceOwn,
            comment = item.comment,
            source = Source.API,
            syncState = initialSyncState(account, time, s),
        )
        val id = db.transactions().insert(tx)
        if (id > 0) mergeOwnTransfer(tx.copy(id = id))
        return id > 0
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
