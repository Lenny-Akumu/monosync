package ua.monosync.widget

import kotlinx.coroutines.flow.first
import ua.monosync.core.Currency
import ua.monosync.core.Money
import ua.monosync.data.Repository
import ua.monosync.data.db.Kind
import ua.monosync.data.db.TransactionEntity
import ua.monosync.data.db.cur
import ua.monosync.data.db.isJar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class WidgetMode(val title: String, val hint: String, val usesAccount: Boolean = false, val allowsAll: Boolean = false) {
    BALANCE("Баланс картки", "Залишок і доступні кошти", usesAccount = true),
    TOTAL("Усі рахунки", "Сума по всіх картках"),
    MONTH("Витрати за місяць", "Скільки пішло з першого числа", usesAccount = true, allowsAll = true),
    TODAY("Витрати сьогодні", "Сума і покупки за день", usesAccount = true, allowsAll = true),
    RECENT("Останні операції", "Стрічка свіжих записів", usesAccount = true, allowsAll = true),
    CATEGORIES("Топ категорій", "Куди йдуть гроші цього місяця"),
    JARS("Банки", "Накопичення і прогрес до цілі"),
    SUBSCRIPTIONS("Найближчі списання", "Підписки, що скоро спишуться");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: BALANCE
    }
}

enum class WidgetStyle(val title: String) {
    COLOR("Кольоровий"), SURFACE("Як система"), CLEAR("Прозорий");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: COLOR
    }
}

const val ALL_ACCOUNTS = "*"

data class WidgetRow(
    val title: String,
    val value: String,
    val sub: String? = null,
    val progress: Float? = null,
    val positive: Boolean = false,
)

data class WidgetData(
    val label: String,
    val headline: String,
    val short: String = headline,
    val caption: String? = null,
    val progress: Float? = null,
    val rows: List<WidgetRow> = emptyList(),
    val empty: String? = null,
)

object WidgetLoader {
    private val uk = Locale.forLanguageTag("uk-UA")
    private val dayFmt = DateTimeFormatter.ofPattern("d MMM", uk)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", uk)

    suspend fun load(repo: Repository, mode: WidgetMode, accountId: String?): WidgetData {
        val s = repo.settings.current()
        val all = repo.db.accounts().all().filter { it.confirmed && !it.hidden }
        val cards = all.filter { !it.isJar }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()

        val primary = cards.firstOrNull { it.id == s.primaryAccountId } ?: cards.firstOrNull()
        val picked = when (accountId) {
            ALL_ACCOUNTS -> null
            null, "" -> primary
            else -> cards.firstOrNull { it.id == accountId } ?: primary
        }
        val scope: (TransactionEntity) -> Boolean = { t ->
            if (accountId == ALL_ACCOUNTS && mode.allowsAll) cards.any { it.id == t.accountId }
            else t.accountId == picked?.id
        }
        val scopeName = if (accountId == ALL_ACCOUNTS && mode.allowsAll) "Усі картки" else picked?.name ?: "monosync"

        if (cards.isEmpty() && mode != WidgetMode.SUBSCRIPTIONS) {
            return WidgetData("monosync", "—", empty = "Додай картку в застосунку")
        }

        val month = repo.range(monthStart, Long.MAX_VALUE).first()
        val categories = repo.db.categories().all()
        val catNames = categories.associate { it.id to it.name }

        return when (mode) {
            WidgetMode.BALANCE -> {
                val a = picked ?: return WidgetData("monosync", "—", empty = "Додай картку в застосунку")
                val spent = month.filter { it.accountId == a.id && it.kind == Kind.EXPENSE }.sumOf { -it.amount }
                val caption = if (a.creditLimit > 0) "Власні ${Money.formatShort(a.balance ?: 0, a.cur)} · ліміт ${Money.formatShort(a.creditLimit, a.cur)}"
                else "За місяць −${Money.formatShort(spent, a.cur)}"
                WidgetData(
                    label = a.name,
                    headline = a.balance?.let { Money.format(it + a.creditLimit, a.cur) } ?: "—",
                    short = a.balance?.let { Money.formatShort(it + a.creditLimit, a.cur) } ?: "—",
                    caption = caption,
                    rows = month.filter { it.accountId == a.id }.take(6).map { txRow(it, catNames, zone, today) },
                )
            }

            WidgetMode.TOTAL -> {
                val sums = sums(cards.filter { it.balance != null }.map { it.cur to (it.balance ?: 0) + it.creditLimit })
                val main = sums.firstOrNull() ?: (Currency.UAH to 0L)
                WidgetData(
                    label = "Усі рахунки",
                    headline = Money.format(main.second, main.first),
                    short = Money.formatShort(main.second, main.first),
                    caption = sums.drop(1).joinToString(" · ") { Money.formatShort(it.second, it.first) }.ifBlank { "${cards.size} рах." },
                    rows = cards.sortedByDescending { it.balance ?: 0 }.map {
                        WidgetRow(it.name, it.balance?.let { b -> Money.formatShort(b + it.creditLimit, it.cur) } ?: "—")
                    },
                )
            }

            WidgetMode.MONTH -> {
                val ex = month.filter { scope(it) && it.kind == Kind.EXPENSE }
                val sums = sums(ex.map { it.cur to -it.amount })
                val main = sums.firstOrNull() ?: ((picked?.cur ?: Currency.UAH) to 0L)
                val budget = categories.mapNotNull { it.budget }.sum().takeIf { it > 0 }
                val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
                val inMain = ex.filter { it.cur == main.first }
                WidgetData(
                    label = "$scopeName · ${today.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, uk)}",
                    headline = Money.format(main.second, main.first),
                    short = Money.formatShort(main.second, main.first),
                    caption = budget?.let { "з ${Money.formatShort(it, main.first)} бюджету" } ?: "${ex.size} покупок",
                    progress = budget?.let { main.second.toFloat() / it },
                    rows = listOf(
                        WidgetRow("Сьогодні", Money.formatShort(inMain.filter { it.time >= dayStart }.sumOf { -it.amount }, main.first)),
                        WidgetRow("За 7 днів", Money.formatShort(inMain.filter { it.time >= weekStart }.sumOf { -it.amount }, main.first)),
                        WidgetRow("В середньому за день", Money.formatShort(main.second / today.dayOfMonth, main.first)),
                    ) + sums.drop(1).map { WidgetRow("У ${it.first.code}", Money.formatShort(it.second, it.first)) },
                )
            }

            WidgetMode.TODAY -> {
                val tx = month.filter { scope(it) && it.time >= dayStart }
                val ex = tx.filter { it.kind == Kind.EXPENSE }
                val sums = sums(ex.map { it.cur to -it.amount })
                val main = sums.firstOrNull() ?: ((picked?.cur ?: Currency.UAH) to 0L)
                WidgetData(
                    label = "Сьогодні · $scopeName",
                    headline = Money.format(main.second, main.first),
                    short = Money.formatShort(main.second, main.first),
                    caption = if (ex.isEmpty()) "Ще без витрат" else "${ex.size} покупок",
                    rows = tx.map { txRow(it, catNames, zone, today) },
                )
            }

            WidgetMode.RECENT -> {
                val tx = repo.db.transactions().observeRecent(60).first().filter(scope).take(12)
                val last = tx.firstOrNull()
                WidgetData(
                    label = if (accountId == ALL_ACCOUNTS) "Останні операції" else scopeName,
                    headline = last?.let { Money.format(it.amount, it.cur, signed = true) } ?: "—",
                    short = last?.let { Money.formatShort(it.amount, it.cur) } ?: "—",
                    caption = last?.let { "${it.description} · ${whenText(it.time, zone, today)}" },
                    rows = tx.drop(1).map { txRow(it, catNames, zone, today) },
                    empty = if (tx.isEmpty()) "Операцій ще немає" else null,
                )
            }

            WidgetMode.CATEGORIES -> {
                val cur = primary?.cur ?: Currency.UAH
                val ex = month.filter { it.kind == Kind.EXPENSE && it.cur == cur && cards.any { a -> a.id == it.accountId } }
                val budgets = categories.associate { it.id to it.budget }
                val by = ex.groupBy { it.categoryId }.mapValues { (_, l) -> l.sumOf { -it.amount } }.toList().sortedByDescending { it.second }
                val total = by.sumOf { it.second }
                val top = by.firstOrNull()
                WidgetData(
                    label = "Цього місяця",
                    headline = Money.format(total, cur),
                    short = top?.let { catNames[it.first] ?: "Інше" } ?: Money.formatShort(total, cur),
                    caption = top?.let { "Найбільше: ${catNames[it.first] ?: "Інше"}" },
                    rows = by.map { (id, v) ->
                        val b = budgets[id]?.takeIf { it > 0 }
                        WidgetRow(
                            catNames[id] ?: "Інше",
                            Money.formatShort(v, cur) + (b?.let { " / " + Money.formatShort(it, cur) } ?: ""),
                            progress = b?.let { v.toFloat() / it } ?: (v.toFloat() / (top?.second ?: 1).coerceAtLeast(1)),
                        )
                    },
                    empty = if (by.isEmpty()) "Витрат цього місяця ще нема" else null,
                )
            }

            WidgetMode.JARS -> {
                val jars = all.filter { it.isJar }.sortedByDescending { it.balance ?: 0 }
                val sums = sums(jars.map { it.cur to (it.balance ?: 0) })
                val main = sums.firstOrNull() ?: (Currency.UAH to 0L)
                val first = jars.firstOrNull()
                WidgetData(
                    label = "Банки",
                    headline = Money.format(main.second, main.first),
                    short = Money.formatShort(main.second, main.first),
                    caption = if (jars.size == 1 && first?.goal != null) "${first.name} · ціль ${Money.formatShort(first.goal, first.cur)}" else "${jars.size} банок",
                    progress = first?.goal?.takeIf { jars.size == 1 && it > 0 }?.let { (first.balance ?: 0).toFloat() / it },
                    rows = jars.map { j ->
                        WidgetRow(
                            j.name,
                            Money.formatShort(j.balance ?: 0, j.cur) + (j.goal?.let { " / " + Money.formatShort(it, j.cur) } ?: ""),
                            progress = j.goal?.takeIf { it > 0 }?.let { (j.balance ?: 0).toFloat() / it },
                        )
                    },
                    empty = if (jars.isEmpty()) "Банок поки нема" else null,
                )
            }

            WidgetMode.SUBSCRIPTIONS -> {
                val subs = repo.db.subscriptions().observeActive().first().filter { it.nextCharge > 0 }
                val next = subs.firstOrNull()
                WidgetData(
                    label = "Найближче списання",
                    headline = next?.name ?: "—",
                    short = next?.let { daysLeft(it.nextCharge, zone, today) } ?: "—",
                    caption = next?.let {
                        "${daysLeft(it.nextCharge, zone, today)} · ${Money.formatShort(it.amount, Currency.byIso(it.currency))}"
                    },
                    rows = subs.drop(1).map {
                        WidgetRow(it.name, Money.formatShort(it.amount, Currency.byIso(it.currency)), sub = daysLeft(it.nextCharge, zone, today))
                    },
                    empty = if (subs.isEmpty()) "Підписок не знайдено" else null,
                )
            }
        }
    }

    private fun sums(items: List<Pair<Currency, Long>>): List<Pair<Currency, Long>> =
        items.groupBy({ it.first }, { it.second }).map { it.key to it.value.sum() }
            .sortedWith(compareByDescending<Pair<Currency, Long>> { p -> items.count { it.first == p.first } }.thenBy { it.first.ordinal })

    private fun txRow(t: TransactionEntity, cats: Map<String, String>, zone: ZoneId, today: LocalDate) = WidgetRow(
        title = t.description,
        value = Money.formatShort(t.amount, t.cur).let { if (t.amount > 0) "+$it" else it },
        sub = listOfNotNull(whenText(t.time, zone, today), t.categoryId?.let { cats[it] }.takeIf { t.kind == Kind.EXPENSE }).joinToString(" · "),
        positive = t.amount > 0 && t.kind == Kind.INCOME,
    )

    private fun whenText(time: Long, zone: ZoneId, today: LocalDate): String {
        val dt = Instant.ofEpochMilli(time).atZone(zone)
        return when (dt.toLocalDate()) {
            today -> timeFmt.format(dt)
            today.minusDays(1) -> "Вчора"
            else -> dayFmt.format(dt)
        }
    }

    private fun daysLeft(at: Long, zone: ZoneId, today: LocalDate): String {
        val d = ChronoUnit.DAYS.between(today, Instant.ofEpochMilli(at).atZone(zone).toLocalDate())
        return when {
            d <= 0 -> "Сьогодні"
            d == 1L -> "Завтра"
            else -> "Через $d дн."
        }
    }
}
