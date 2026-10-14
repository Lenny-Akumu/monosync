package ua.monosync.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

enum class Period(val days: Int, val title: String) {
    WEEKLY(7, "щотижня"),
    MONTHLY(30, "щомісяця"),
    YEARLY(365, "щороку");

    fun next(from: Long, zone: ZoneId): Long {
        val t = Instant.ofEpochMilli(from).atZone(zone)
        return when (this) {
            WEEKLY -> t.plusWeeks(1)
            MONTHLY -> t.plusMonths(1)
            YEARLY -> t.plusYears(1)
        }.toInstant().toEpochMilli()
    }

    companion object {
        fun byName(name: String?) = entries.firstOrNull { it.name == name } ?: MONTHLY
    }
}

data class Charge(
    val name: String,
    val time: Long,
    val amountMinor: Long,
    val currencyIso: Int,
    val accountId: String,
    val subscriptionHint: Boolean,
)

data class DetectedSubscription(
    val key: String,
    val name: String,
    val period: Period,
    val amountMinor: Long,
    val currencyIso: Int,
    val accountId: String,
    val lastCharge: Long,
    val nextCharge: Long,
    val charges: Int,
)

object SubscriptionDetector {
    private const val DAY = 24 * 3600_000L

    private val knownServices = listOf(
        "netflix", "spotify", "youtube", "apple.com", "icloud", "itunes", "google one", "google storage",
        "claude", "anthropic", "openai", "chatgpt", "discord", "render", "github", "notion", "adobe",
        "megogo", "sweet.tv", "sweet tv", "kyivstar tv", "oll.tv", "takflix", "disney", "hbo", "amazon prime",
        "playstation", "xbox", "nintendo", "steam", "twitch", "patreon", "boosty", "duolingo", "figma",
        "canva", "dropbox", "microsoft", "office 365", "1password", "nordvpn", "expressvpn", "proton",
        "telegram premium", "midjourney", "cursor", "jetbrains", "linkedin", "tinder", "bumble",
    )

    fun isKnownService(name: String): Boolean {
        val n = name.lowercase()
        return knownServices.any { n.contains(it) }
    }

    fun key(name: String): String =
        name.lowercase()
            .replace(Regex("https?://|www\\."), " ")
            .replace(Regex("\\.(com|net|org|io|ua|tv|app)\\b"), " ")
            .replace(Regex("\\d{3,}"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString(" ")

    fun detect(charges: List<Charge>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<DetectedSubscription> =
        charges.filter { it.amountMinor > 0 }
            .groupBy { key(it.name) }
            .filterKeys { it.length >= 3 }
            .mapNotNull { (k, list) -> detectOne(k, list.sortedBy { it.time }, now, zone) }

    private fun detectOne(key: String, list: List<Charge>, now: Long, zone: ZoneId): DetectedSubscription? {
        val last = list.last()
        val hint = list.any { it.subscriptionHint || isKnownService(it.name) }
        val amount = median(list.takeLast(4).map { it.amountMinor })

        val period: Period = if (list.size == 1) {
            if (!hint) return null
            Period.MONTHLY
        } else {
            val gaps = list.zipWithNext { a, b -> (b.time - a.time).toDouble() / DAY }.filter { it >= 3 }
            if (gaps.isEmpty()) {
                if (!hint) return null
                Period.MONTHLY
            } else {
                val base = gaps.min()
                val p = when (base) {
                    in 6.0..8.5 -> Period.WEEKLY
                    in 25.0..36.0 -> Period.MONTHLY
                    in 340.0..390.0 -> Period.YEARLY
                    else -> return null
                }
                val (unit, tolerance) = when (p) {
                    Period.WEEKLY -> 7.0 to 1.5
                    Period.MONTHLY -> 30.44 to 5.0
                    Period.YEARLY -> 365.25 to 20.0
                }
                // A skipped month still counts: the gap is then a multiple of the period.
                val regular = gaps.all { g ->
                    val n = Math.round(g / unit).coerceAtLeast(1)
                    abs(g - n * unit) <= tolerance * n
                }
                if (!regular) return null
                val needed = when {
                    hint -> 2
                    p == Period.WEEKLY -> 4
                    p == Period.YEARLY -> 2
                    else -> 3
                }
                if (list.size < needed) return null
                val similar = list.takeLast(4).all { abs(it.amountMinor - amount) <= maxOf(amount / 5, 200) }
                if (!similar && !hint) return null
                p
            }
        }

        if (now - last.time > period.days * 2L * DAY + 10 * DAY) return null

        var next = period.next(last.time, zone)
        while (next < now - 5 * DAY) next = period.next(next, zone)

        return DetectedSubscription(
            key = key,
            name = last.name.trim(),
            period = period,
            amountMinor = amount,
            currencyIso = last.currencyIso,
            accountId = last.accountId,
            lastCharge = last.time,
            nextCharge = next,
            charges = list.size,
        )
    }

    private fun median(values: List<Long>): Long {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
