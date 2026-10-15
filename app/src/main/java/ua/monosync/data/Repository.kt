package ua.monosync.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ua.monosync.core.CardKeys
import ua.monosync.core.Currency
import ua.monosync.core.Direction
import ua.monosync.core.Charge
import ua.monosync.core.Mcc
import ua.monosync.core.Period
import ua.monosync.core.SubscriptionDetector
import ua.monosync.core.NotificationParser
import ua.monosync.core.ParsedNotification
import ua.monosync.data.db.AccountEntity
import ua.monosync.data.db.AppDatabase
import ua.monosync.data.db.CategoryEntity
import ua.monosync.data.db.Kind
import ua.monosync.data.db.RawNotificationEntity
import ua.monosync.data.db.RuleEntity
import ua.monosync.data.db.Source
import ua.monosync.data.db.SubscriptionEntity
import ua.monosync.data.db.SyncState
import ua.monosync.data.db.TransactionEntity
import ua.monosync.data.db.JAR_TYPE
import ua.monosync.data.db.cur
import ua.monosync.data.db.isJar
import ua.monosync.data.remote.MonoAccount
import ua.monosync.data.remote.EnableBankingApi
import ua.monosync.data.remote.MonoApi
import ua.monosync.data.remote.MonoJar
import ua.monosync.data.remote.MonoStatementItem
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

class Repository(
    private val context: Context,
    val db: AppDatabase,
    val settings: Settings,
    val secrets: Secrets,
) {
    val mono = MonoApi { secrets.monoToken.value }
    val eb = EnableBankingApi({ ebAppId }, { secrets.ebKey.value })
    @Volatile var ebAppId: String? = null
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

        if (parsed.jarName != null) return ingestJar(parsed, rawId, postedAt, title, text, settingsNow)

        val (account, newAccount) = resolveCard(parsed, settingsNow)

        val amountAbs = parsed.accountAmountMinor(account.cur)
        val signed = if (parsed.direction == Direction.IN) amountAbs else -amountAbs
        val kind = if (signed > 0) Kind.INCOME else Kind.EXPENSE
        val description = parsed.description ?: if (signed > 0) "Поповнення" else "Переказ з картки"
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

    /** Finds the card a notification talks about, or creates a placeholder for a card we have not seen. */
    private suspend fun resolveCard(parsed: ParsedNotification, s: AppSettings, guessIfUnnamed: Boolean = true): Pair<AccountEntity, Boolean> {
        val cardKey = parsed.cardKey
        val existing = cardKey?.let { db.accounts().byKey(it) }.orEmpty()
        existing.firstOrNull { it.confirmed }?.let { return it to false }
        existing.firstOrNull()?.let { return it to false }
        if (cardKey == null && guessIfUnnamed) {
            val cards = db.accounts().all().filter { !it.isJar && !it.hidden }
            val guess = cards.firstOrNull { it.id == s.primaryAccountId }
                ?: cards.firstOrNull { it.confirmed && it.cur == (parsed.currency ?: Currency.UAH) }
                ?: cards.firstOrNull { it.confirmed }
            if (guess != null) return guess to false
        }
        val k = cardKey ?: "картк"
        val currency = CardKeys.guessCurrency(k) ?: parsed.currency ?: Currency.UAH
        val created = AccountEntity(
            id = "card:$k",
            name = parsed.cardPhrase?.let { CardKeys.displayName(it) } ?: "Картка Mono",
            cardKey = k,
            type = CardKeys.guessType(k),
            currency = currency.iso,
            confirmed = false,
        )
        db.accounts().upsert(created)
        return created to true
    }

    private suspend fun resolveJar(parsed: ParsedNotification): Pair<AccountEntity, Boolean> {
        val key = parsed.jarKey!!
        val existing = db.accounts().byKey(key)
        existing.firstOrNull { it.confirmed }?.let { return it to false }
        existing.firstOrNull()?.let { return it to false }
        val jar = AccountEntity(
            id = key,
            name = "Банка «${parsed.jarName}»",
            cardKey = key,
            type = JAR_TYPE,
            currency = (parsed.currency ?: Currency.UAH).iso,
            confirmed = false,
        )
        db.accounts().upsert(jar)
        return jar to true
    }

    /** Jar operations: own top-ups and withdrawals are transfers, someone else's top-up is income of the jar. */
    private suspend fun ingestJar(
        parsed: ParsedNotification,
        rawId: Long,
        postedAt: Long,
        title: String?,
        text: String?,
        s: AppSettings,
    ): IngestResult {
        val (jar, newJar) = resolveJar(parsed)
        val intoJar = parsed.jarFlow != Direction.OUT
        val amount = parsed.amountMinor

        val jarBalance = parsed.jarBalanceMinor
            ?: jar.balance?.let { if (intoJar) it + amount else it - amount }
        if (jarBalance != null) db.accounts().upsert(jar.copy(balance = jarBalance, updatedAt = postedAt))

        val card = if (parsed.externalJarTopUp) null else resolveCard(parsed, s)
        val raw = listOfNotNull(title, text).joinToString("\n")
        val tx = if (card != null) {
            val (acc, _) = card
            TransactionEntity(
                accountId = acc.id,
                transferAccountId = jar.id,
                time = postedAt,
                amount = if (intoJar) -amount else amount,
                currency = acc.currency,
                description = parsed.description ?: if (intoJar) "Поповнення банки «${parsed.jarName}»" else "З банки «${parsed.jarName}»",
                kind = Kind.TRANSFER,
                balanceAfter = parsed.balanceMinor.takeIf { parsed.cardKey != null },
                comment = parsed.notes.joinToString("\n").ifBlank { null },
                source = Source.NOTIFICATION,
                rawText = raw,
                syncState = initialSyncState(acc, postedAt, s),
            )
        } else {
            TransactionEntity(
                accountId = jar.id,
                time = postedAt,
                amount = if (intoJar) amount else -amount,
                currency = jar.currency,
                description = parsed.counterpartPhrase?.let { "Від $it" } ?: "Поповнення банки «${parsed.jarName}»",
                kind = if (intoJar) Kind.INCOME else Kind.EXPENSE,
                balanceAfter = jarBalance,
                source = Source.NOTIFICATION,
                rawText = raw,
                syncState = initialSyncState(jar, postedAt, s),
            )
        }
        val id = db.transactions().insert(tx)
        db.raw().mark(rawId, true, id)
        card?.first?.let { acc ->
            if (parsed.cardKey != null && parsed.balanceMinor != null) db.accounts().upsert(acc.copy(balance = parsed.balanceMinor, updatedAt = postedAt))
        }
        val newCard = card?.second == true
        val reported = when {
            newJar -> db.accounts().byId(jar.id) ?: jar
            card != null -> db.accounts().byId(card.first.id) ?: card.first
            else -> db.accounts().byId(jar.id) ?: jar
        }
        return IngestResult.Recorded(db.transactions().byId(id) ?: tx.copy(id = id), reported, newJar || newCard)
    }

    private fun notionActive(s: AppSettings) = s.notionEnabled && s.notionStartAt > 0

    private fun initialSyncState(account: AccountEntity, time: Long, s: AppSettings): String =
        if (!notionActive(s) || !account.confirmed || time < s.notionStartAt) SyncState.SKIPPED else SyncState.PENDING

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

    suspend fun addLocalCategory(name: String, budget: Long?, groupKey: String?): String {
        val id = "user:" + java.util.UUID.randomUUID().toString()
        db.categories().upsert(listOf(CategoryEntity(id, name.trim(), budget, groupKey, fromNotion = false, sortOrder = -1)))
        return id
    }

    suspend fun deleteCategory(c: CategoryEntity) {
        db.transactions().remapCategory(c.id, null)
        db.rules().deleteByCategory(c.id)
        db.categories().delete(c.id)
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
        val state = if (acc?.confirmed == true && notionActive(s) && tx.time >= s.notionStartAt) SyncState.PENDING else SyncState.SKIPPED
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
        val s = settings.current()
        if (notionActive(s)) db.transactions().releaseForAccount(account.id, s.notionStartAt)
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
        info.jars.forEachIndexed { index, j -> upsertApiJar(j, 100 + index, known)?.let(newOnes::add) }
        return newOnes
    }

    private suspend fun upsertApiJar(j: MonoJar, index: Int, known: List<AccountEntity>): AccountEntity? {
        val title = j.title?.trim().orEmpty().ifBlank { "Банка" }
        val key = CardKeys.jarKey(title)
        val byId = known.firstOrNull { it.id == j.id }
        if (byId != null) {
            db.accounts().upsert(byId.copy(balance = j.balance, goal = j.goal, cardKey = key, updatedAt = System.currentTimeMillis()))
            return null
        }
        val placeholder = known.firstOrNull { !it.fromApi && it.cardKey == key }
        val entity = AccountEntity(
            id = j.id,
            name = placeholder?.name ?: "Банка «$title»",
            cardKey = key,
            type = JAR_TYPE,
            currency = j.currencyCode,
            balance = j.balance,
            goal = j.goal,
            confirmed = placeholder?.confirmed ?: false,
            hidden = placeholder?.hidden ?: false,
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

        if (match == null) {
            // The other side of a transfer we already recorded on another account (card -> jar and back).
            val mirrored = db.transactions().transfersTouching(account.id, time - 15 * 60_000L, time + 15 * 60_000L)
                .any { t -> (t.amount > 0) != (item.amount > 0) && abs(abs(t.amount) - abs(item.amount)) <= maxOf(2L, abs(item.amount) / 100) }
            if (mirrored) return false
        }

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

    // Enable Banking (European banks over PSD2)

    data class EbConnectResult(val newAccounts: List<AccountEntity>)

    suspend fun ebConnect(code: String): EbConnectResult {
        val s = settings.current()
        ebAppId = s.eb.appId
        val session = eb.createSession(code)
        secrets.ebSession.value?.let { old -> if (old != session.id) eb.deleteSession(old) }
        secrets.setEbSession(session.id)
        settings.updateEb { it.copy(validUntil = session.validUntil, lastError = null, pendingState = null) }
        val known = db.accounts().all().associateBy { it.id }
        val fresh = mutableListOf<AccountEntity>()
        session.accounts.forEachIndexed { i, a ->
            val id = "eb:" + a.uid
            val currency = Currency.byCode(a.currency) ?: Currency.byCode("NOK") ?: Currency.EUR
            val existing = known[id]
            if (existing == null) {
                val tail = a.iban?.takeLast(4)
                val entity = AccountEntity(
                    id = id,
                    name = a.name?.takeIf { it.isNotBlank() } ?: listOfNotNull(s.eb.aspspName.ifBlank { null }, tail?.let { "••$it" }).joinToString(" "),
                    cardKey = id,
                    currency = currency.iso,
                    maskedPan = tail,
                    iban = a.iban,
                    fromApi = true,
                    sortOrder = 200 + i,
                )
                db.accounts().upsert(entity)
                fresh += entity
            }
        }
        return EbConnectResult(fresh)
    }

    /** Pulls balances and booked transactions of every linked European account. */
    suspend fun ebSync(full: Boolean = false): Int = lock.withLock {
        val s = settings.current()
        ebAppId = s.eb.appId
        if (secrets.ebSession.value == null) return 0
        if (s.eb.validUntil in 1 until System.currentTimeMillis()) {
            settings.updateEb { it.copy(lastError = "Доступ до банку закінчився, увійди ще раз через BankID") }
            return 0
        }
        val zone = ZoneId.systemDefault()
        var created = 0
        val accounts = db.accounts().all().filter { it.id.startsWith("eb:") && !it.hidden }
        for (acc in accounts) {
            val uid = acc.id.removePrefix("eb:")
            eb.balance(uid)?.let { b -> db.accounts().upsert(acc.copy(balance = b.amountMinor, updatedAt = System.currentTimeMillis())) }
            val since = if (full || acc.lastStatementAt == 0L) System.currentTimeMillis() - s.historyDays * day
            else acc.lastStatementAt - 5 * day
            val dateFrom = Instant.ofEpochMilli(since).atZone(zone).toLocalDate().toString()
            for (t in eb.transactions(uid, dateFrom)) {
                if (!t.booked) continue
                val date = t.date?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: continue
                val time = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                val description = (t.counterparty ?: t.remittance ?: if (t.credit) "Надходження" else "Списання").take(120)
                val extId = "eb:" + (t.id ?: sha("$uid|${t.date}|${t.amountMinor}|${t.credit}|$description"))
                val kind = if (t.credit) Kind.INCOME else Kind.EXPENSE
                val fresh = db.accounts().byId(acc.id) ?: acc
                val id = db.transactions().insert(
                    TransactionEntity(
                        monoId = extId,
                        accountId = acc.id,
                        time = time,
                        amount = if (t.credit) t.amountMinor else -t.amountMinor,
                        currency = acc.currency,
                        description = description,
                        mcc = t.mcc,
                        categoryId = if (kind == Kind.EXPENSE) pickCategory(description, t.mcc) else null,
                        kind = kind,
                        balanceAfter = t.balanceAfterMinor,
                        comment = t.remittance?.takeIf { it != description },
                        source = Source.API,
                        syncState = initialSyncState(fresh, time, s),
                    )
                )
                if (id > 0) {
                    created++
                    db.transactions().byId(id)?.let { mergeOwnTransfer(it) }
                }
            }
            db.accounts().byId(acc.id)?.let { db.accounts().upsert(it.copy(lastStatementAt = System.currentTimeMillis())) }
        }
        settings.updateEb { it.copy(lastSync = System.currentTimeMillis(), lastError = null) }
        created
    }

    suspend fun ebDisconnect() {
        settings.current().eb.appId.let { ebAppId = it }
        secrets.ebSession.value?.let { eb.deleteSession(it) }
        secrets.setEbSession(null)
        settings.updateEb { it.copy(validUntil = 0, lastSync = 0, lastError = null) }
    }

    // Subscriptions

    private val day = 24 * 3600_000L

    /** Looks for regular charges in the last year and keeps the subscriptions table in step. */
    suspend fun refreshSubscriptions() {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val cats = db.categories().all().associateBy { it.id }
        val charges = db.transactions().expensesSince(now - 420 * day)
            .filter { !it.genericDescription }
            .map { tx ->
                val cat = tx.categoryId?.let { cats[it] }
                Charge(
                    name = tx.description,
                    time = tx.time,
                    amountMinor = -tx.amount,
                    currencyIso = tx.currency,
                    accountId = tx.accountId,
                    subscriptionHint = cat?.groupKey == "digital" ||
                        cat?.name?.lowercase()?.contains("підпис") == true ||
                        Mcc.group(tx.mcc)?.key == "digital",
                )
            }
        val existing = db.subscriptions().all().associateBy { it.key }
        for (d in SubscriptionDetector.detect(charges, now, zone)) {
            val e = existing[d.key]
            if (e == null) {
                db.subscriptions().insert(
                    SubscriptionEntity(
                        key = d.key, name = d.name, amount = d.amountMinor, currency = d.currencyIso,
                        accountId = d.accountId, period = d.period.name, lastCharge = d.lastCharge, nextCharge = d.nextCharge,
                    )
                )
            } else if (d.lastCharge > e.lastCharge || (!e.userEdited && e.nextCharge != d.nextCharge)) {
                val period = if (e.userEdited) Period.byName(e.period) else d.period
                db.subscriptions().update(
                    e.copy(
                        name = if (e.userEdited) e.name else d.name,
                        amount = if (e.userEdited) e.amount else d.amountMinor,
                        period = period.name,
                        lastCharge = maxOf(e.lastCharge, d.lastCharge),
                        nextCharge = rollForward(period.next(maxOf(e.lastCharge, d.lastCharge), zone), period, now),
                    )
                )
            }
        }
        db.subscriptions().all().filter { !it.dismissed && it.nextCharge < now - 5 * day }.forEach {
            db.subscriptions().update(it.copy(nextCharge = rollForward(it.nextCharge, Period.byName(it.period), now)))
        }
    }

    private fun rollForward(from: Long, period: Period, now: Long): Long {
        var next = from
        while (next < now - 5 * day) next = period.next(next, ZoneId.systemDefault())
        return next
    }

    suspend fun markAsSubscription(tx: TransactionEntity) {
        val key = SubscriptionDetector.key(tx.description)
        val existing = db.subscriptions().all().firstOrNull { it.key == key }
        val next = rollForward(Period.MONTHLY.next(tx.time, ZoneId.systemDefault()), Period.MONTHLY, System.currentTimeMillis())
        if (existing != null) {
            db.subscriptions().update(existing.copy(dismissed = false, lastCharge = maxOf(existing.lastCharge, tx.time)))
        } else {
            db.subscriptions().insert(
                SubscriptionEntity(
                    key = key, name = tx.description, amount = abs(tx.amount), currency = tx.currency, accountId = tx.accountId,
                    period = Period.MONTHLY.name, lastCharge = tx.time, nextCharge = next, manual = true, userEdited = true,
                )
            )
        }
    }

    /** Subscriptions whose reminder is due now; marks them as reminded. */
    suspend fun dueReminders(): List<Pair<SubscriptionEntity, Int>> {
        val s = settings.current()
        if (!s.subRemindersOn) return emptyList()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return db.subscriptions().all()
            .filter { !it.dismissed && it.remind && it.notifiedFor != it.nextCharge }
            .mapNotNull { sub ->
                val dueDay = Instant.ofEpochMilli(sub.nextCharge).atZone(zone).toLocalDate()
                val daysLeft = ChronoUnit.DAYS.between(today, dueDay).toInt()
                if (daysLeft < 0 || daysLeft > s.subRemindDays || sub.nextCharge < now - day) return@mapNotNull null
                db.subscriptions().update(sub.copy(notifiedFor = sub.nextCharge))
                sub to daysLeft
            }
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
