package ua.monosync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class SubscriptionDetectorTest {
    private val zone = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun c(name: String, t: Long, amount: Long, hint: Boolean = false) = Charge(name, t, amount, 980, "acc", hint)

    @Test
    fun monthlyGenericMerchantNeedsThreeCharges() {
        val two = listOf(c("Фітнес Клуб", at(2026, 8, 5), 90000), c("Фітнес Клуб", at(2026, 9, 5), 90000))
        assertTrue(SubscriptionDetector.detect(two, at(2026, 9, 10), zone).isEmpty())
        val three = two + c("Фітнес Клуб", at(2026, 10, 5), 90000)
        val s = SubscriptionDetector.detect(three, at(2026, 10, 10), zone).single()
        assertEquals(Period.MONTHLY, s.period)
        assertEquals(at(2026, 11, 5), s.nextCharge)
    }

    @Test
    fun knownServiceFromOneCharge() {
        val s = SubscriptionDetector.detect(listOf(c("NETFLIX.COM", at(2026, 10, 2), 29900)), at(2026, 10, 14), zone).single()
        assertEquals("netflix", s.key)
        assertEquals(at(2026, 11, 2), s.nextCharge)
    }

    @Test
    fun skippedMonthStillMonthly() {
        val list = listOf(
            c("Render", at(2026, 6, 7), 1636, hint = true),
            c("Render", at(2026, 8, 7), 1636, hint = true),
            c("Render", at(2026, 9, 7), 1636, hint = true),
        )
        assertEquals(Period.MONTHLY, SubscriptionDetector.detect(list, at(2026, 10, 1), zone).single().period)
    }

    @Test
    fun randomShopIsNotSubscription() {
        val list = listOf(
            c("АТБ-Маркет", at(2026, 9, 1), 41280),
            c("АТБ-Маркет", at(2026, 9, 3), 10500),
            c("АТБ-Маркет", at(2026, 9, 12), 76000),
            c("АТБ-Маркет", at(2026, 9, 20), 23000),
        )
        assertTrue(SubscriptionDetector.detect(list, at(2026, 9, 25), zone).isEmpty())
    }

    @Test
    fun weekly() {
        val list = (0 until 4).map { c("Дитячий гурток", at(2026, 9, 1 + it * 7), 40000) }
        assertEquals(Period.WEEKLY, SubscriptionDetector.detect(list, at(2026, 9, 25), zone).single().period)
    }

    @Test
    fun staleSubscriptionDropped() {
        val list = listOf(c("Spotify", at(2026, 1, 3), 19900), c("Spotify", at(2026, 2, 3), 19900))
        assertTrue(SubscriptionDetector.detect(list, at(2026, 10, 1), zone).isEmpty())
    }
}
